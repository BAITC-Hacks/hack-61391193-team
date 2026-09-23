package astana.innovation.backendakim.simulation;

import astana.innovation.backendakim.catalog.CatalogService;
import astana.innovation.backendakim.catalog.DistrictResponse;
import astana.innovation.backendakim.catalog.MeasureResponse;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Exact exhaustive search over the fixed catalogue, with no LLM calls or rounded scores. */
@Component
public class SimulationOptimizer {
    private final CatalogService catalog;
    private final SimulationValidator validator;
    private final ScoreCalculator calculator;
    private volatile Optimization cached;

    public SimulationOptimizer(CatalogService catalog, SimulationValidator validator, ScoreCalculator calculator) {
        this.catalog = catalog;
        this.validator = validator;
        this.calculator = calculator;
    }

    /** The catalogue and rules are immutable for the application's lifetime. */
    public Optimization optimal() {
        Optimization result = cached;
        if (result == null) {
            synchronized (this) {
                result = cached;
                if (result == null) cached = result = new Search().run();
            }
        }
        return result;
    }

    public record Optimization(SimulationRequest request, ScoreCalculator.Calculation calculation,
                               long evaluatedCandidates) { }

    private final class Search {
        private final List<MeasureResponse> measures = catalog.getMeasures().stream()
                .sorted(Comparator.comparingInt(m -> measureNumber(m.id()))).toList();
        private final List<DistrictResponse> districts = catalog.getDistricts().stream()
                .sorted(Comparator.comparing(DistrictResponse::id)).toList();
        private final List<String> metrics = List.copyOf(SimulationRules.METRIC_WEIGHTS.keySet());
        private final Map<String, BigDecimal[]> effects = new HashMap<>();
        private SimulationRequest bestRequest;
        private BigDecimal bestScore;
        private int bestCost;
        private long evaluatedCandidates;

        Optimization run() {
            for (MeasureResponse measure : measures) {
                BigDecimal factor = BigDecimal.valueOf(SimulationRules.HORIZON - measure.lagQuarters())
                        .divide(BigDecimal.valueOf(SimulationRules.HORIZON));
                BigDecimal[] realized = new BigDecimal[metrics.size()];
                for (int m = 0; m < metrics.size(); m++) {
                    realized[m] = BigDecimal.valueOf(measure.fullEffects().getOrDefault(metrics.get(m), 0))
                            .multiply(factor);
                }
                effects.put(measure.id(), realized);
            }
            chooseMeasures(0, new ArrayList<>(), new HashMap<>(), 0);
            if (bestRequest == null) throw new IllegalStateException("No valid five-decision plan in the catalogue");
            // The canonical validator and calculator remain the authority for the returned result.
            var calculation = calculator.calculate(validator.validate(bestRequest));
            if (calculation.summary().finalScore().compareTo(bestScore) != 0) {
                throw new IllegalStateException("Optimizer score differs from the simulation calculator");
            }
            return new Optimization(bestRequest, calculation, evaluatedCandidates);
        }

        private void chooseMeasures(int start, List<MeasureResponse> chosen, Map<String, Integer> categories, int cost) {
            if (chosen.size() == SimulationRules.DECISIONS) {
                assignDistricts(chosen, cost);
                return;
            }
            int needed = SimulationRules.DECISIONS - chosen.size();
            for (int index = start; index <= measures.size() - needed; index++) {
                MeasureResponse measure = measures.get(index);
                int categoryCount = categories.getOrDefault(measure.categoryId(), 0);
                if (cost + measure.cost() > SimulationRules.BUDGET
                        || categoryCount >= SimulationRules.MAX_PER_CATEGORY
                        || hasGlobalConflict(chosen, measure)) continue;
                chosen.add(measure);
                categories.put(measure.categoryId(), categoryCount + 1);
                chooseMeasures(index + 1, chosen, categories, cost + measure.cost());
                chosen.removeLast();
                categories.put(measure.categoryId(), categoryCount);
            }
        }

        private boolean hasGlobalConflict(List<MeasureResponse> chosen, MeasureResponse candidate) {
            return SimulationRules.CONFLICTS.stream().filter(rule -> !rule.sameDistrictOnly())
                    .anyMatch(rule -> chosen.stream().anyMatch(other -> matches(rule, other.id(), candidate.id())));
        }

        private void assignDistricts(List<MeasureResponse> chosen, int cost) {
            List<MeasureResponse> local = chosen.stream().filter(m -> !"city".equals(m.scope())).toList();
            int maskCount = 1 << local.size();
            boolean[] validMasks = new boolean[maskCount];
            BigDecimal[] cityEffects = new BigDecimal[metrics.size()];
            BigDecimal[][] localEffects = new BigDecimal[local.size()][];
            for (int metric = 0; metric < metrics.size(); metric++) {
                cityEffects[metric] = BigDecimal.ZERO;
                for (MeasureResponse measure : chosen) {
                    if ("city".equals(measure.scope())) {
                        cityEffects[metric] = cityEffects[metric].add(effects.get(measure.id())[metric]);
                    }
                }
            }
            for (int index = 0; index < local.size(); index++) {
                MeasureResponse measure = local.get(index);
                localEffects[index] = effects.get(measure.id()).clone();
                for (var rule : SimulationRules.SYNERGIES) {
                    if (rule.districtMeasureId().equals(measure.id())
                            && chosen.stream().anyMatch(m -> rule.cityMeasureId().equals(m.id()))) {
                        int metric = metrics.indexOf(rule.metric());
                        localEffects[index][metric] = localEffects[index][metric].add(BigDecimal.valueOf(rule.bonus()));
                    }
                }
            }
            // A district can receive any subset of the <=5 local measures. Reuse its exact result
            // in every assignment, instead of constructing a complete response for each candidate.
            DistrictOutcome[][] outcomes = new DistrictOutcome[districts.size()][maskCount];
            for (int mask = 0; mask < maskCount; mask++) {
                validMasks[mask] = validLocalSubset(local, mask);
                if (!validMasks[mask]) continue;
                for (int district = 0; district < districts.size(); district++) {
                    DistrictResponse target = districts.get(district);
                    BigDecimal score = BigDecimal.ZERO;
                    int critical = 0;
                    for (int metric = 0; metric < metrics.size(); metric++) {
                        String key = metrics.get(metric);
                        BigDecimal value = BigDecimal.valueOf(target.metrics().get(key)).add(cityEffects[metric]);
                        for (int index = 0; index < local.size(); index++) {
                            if ((mask & (1 << index)) != 0) value = value.add(localEffects[index][metric]);
                        }
                        value = ScoreCalculator.clip(value);
                        score = score.add(value.multiply(SimulationRules.METRIC_WEIGHTS.get(key)));
                        if (value.compareTo(SimulationRules.CRITICAL_THRESHOLD) < 0) critical++;
                    }
                    BigDecimal contribution = score.multiply(BigDecimal.valueOf(target.populationShare()))
                            .multiply(SimulationRules.CITY_WEIGHT).subtract(BigDecimal.valueOf(critical));
                    outcomes[district][mask] = new DistrictOutcome(score, contribution);
                }
            }
            distribute(chosen, local, cost, outcomes, validMasks, 0, maskCount - 1,
                    new int[districts.size()], BigDecimal.ZERO, null);
        }

        private boolean validLocalSubset(List<MeasureResponse> local, int mask) {
            for (var rule : SimulationRules.CONFLICTS) {
                if (!rule.sameDistrictOnly()) continue;
                boolean first = false;
                boolean second = false;
                for (int index = 0; index < local.size(); index++) {
                    if ((mask & (1 << index)) == 0) continue;
                    first |= rule.firstMeasureId().equals(local.get(index).id());
                    second |= rule.secondMeasureId().equals(local.get(index).id());
                }
                if (first && second) return false;
            }
            return true;
        }

        private void distribute(List<MeasureResponse> chosen, List<MeasureResponse> local, int cost,
                                DistrictOutcome[][] outcomes, boolean[] validMasks, int district,
                                int remaining, int[] assigned, BigDecimal contribution, BigDecimal minimum) {
            if (district == districts.size()) return;
            boolean last = district == districts.size() - 1;
            for (int subset = remaining; ; subset = (subset - 1) & remaining) {
                if (validMasks[subset]) {
                    assigned[district] = subset;
                    DistrictOutcome outcome = outcomes[district][subset];
                    BigDecimal nextContribution = contribution.add(outcome.contribution());
                    BigDecimal nextMinimum = minimum == null ? outcome.score() : minimum.min(outcome.score());
                    if (last) {
                        evaluatedCandidates++;
                        consider(chosen, local, cost, assigned,
                                nextContribution.add(nextMinimum.multiply(SimulationRules.WEAKEST_WEIGHT)));
                    } else {
                        distribute(chosen, local, cost, outcomes, validMasks, district + 1, remaining ^ subset,
                                assigned, nextContribution, nextMinimum);
                    }
                }
                // The final district must receive every remaining measure; elsewhere enumerate all subsets.
                if (last || subset == 0) break;
            }
        }

        private void consider(List<MeasureResponse> chosen, List<MeasureResponse> local, int cost,
                              int[] assigned, BigDecimal score) {
            int comparison = bestScore == null ? 1 : score.compareTo(bestScore);
            if (comparison < 0 || comparison == 0 && cost > bestCost) return;
            List<SimulationRequest.Decision> decisions = new ArrayList<>();
            for (MeasureResponse measure : chosen) {
                String districtId = null;
                if (!"city".equals(measure.scope())) {
                    int bit = 1 << local.indexOf(measure);
                    for (int district = 0; district < assigned.length; district++) {
                        if ((assigned[district] & bit) != 0) districtId = districts.get(district).id();
                    }
                }
                decisions.add(new SimulationRequest.Decision(measure.id(), districtId));
            }
            SimulationRequest candidate = new SimulationRequest(List.copyOf(decisions));
            if (comparison == 0 && cost == bestCost && comparePlans(candidate, bestRequest) >= 0) return;
            bestScore = score;
            bestCost = cost;
            bestRequest = candidate;
        }
    }

    private record DistrictOutcome(BigDecimal score, BigDecimal contribution) { }

    private static boolean matches(SimulationRules.ConflictRule rule, String first, String second) {
        return rule.firstMeasureId().equals(first) && rule.secondMeasureId().equals(second)
                || rule.firstMeasureId().equals(second) && rule.secondMeasureId().equals(first);
    }

    private static int measureNumber(String id) { return Integer.parseInt(id.substring(1)); }

    private static int comparePlans(SimulationRequest first, SimulationRequest second) {
        for (int index = 0; index < first.decisions().size(); index++) {
            var a = first.decisions().get(index);
            var b = second.decisions().get(index);
            int comparison = Integer.compare(measureNumber(a.measureId()), measureNumber(b.measureId()));
            if (comparison != 0) return comparison;
            comparison = Comparator.nullsFirst(String::compareTo).compare(a.districtId(), b.districtId());
            if (comparison != 0) return comparison;
        }
        return 0;
    }
}
