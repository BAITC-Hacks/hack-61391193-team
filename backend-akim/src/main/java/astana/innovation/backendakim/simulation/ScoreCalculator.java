package astana.innovation.backendakim.simulation;

import astana.innovation.backendakim.catalog.CatalogService;
import astana.innovation.backendakim.catalog.DistrictResponse;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static astana.innovation.backendakim.simulation.SimulationResult.*;
import static astana.innovation.backendakim.simulation.SimulationValidator.SelectedMeasure;

/** Pure deterministic arithmetic. No LLM, external statistics, or intermediate rounding. */
@Component
public class ScoreCalculator {
    private final CatalogService catalog;

    public ScoreCalculator(CatalogService catalog) { this.catalog = catalog; }

    Calculation calculate(List<SelectedMeasure> selected) {
        Map<String, Map<String, BigDecimal>> before = new LinkedHashMap<>();
        Map<String, Map<String, BigDecimal>> after = new LinkedHashMap<>();
        for (DistrictResponse district : catalog.getDistricts()) {
            Map<String, BigDecimal> metrics = new LinkedHashMap<>();
            district.metrics().forEach((key, value) -> metrics.put(key, BigDecimal.valueOf(value)));
            before.put(district.id(), metrics);
            after.put(district.id(), new LinkedHashMap<>(metrics));
        }

        List<MeasureEffect> effects = new ArrayList<>();
        Map<String, SelectedMeasure> byId = new LinkedHashMap<>();
        for (SelectedMeasure selection : selected) {
            var measure = selection.measure();
            byId.put(measure.id(), selection);
            BigDecimal factor = BigDecimal.valueOf(SimulationRules.HORIZON - measure.lagQuarters())
                    .divide(BigDecimal.valueOf(SimulationRules.HORIZON));
            Map<String, BigDecimal> realized = new LinkedHashMap<>();
            measure.fullEffects().forEach((key, value) -> realized.put(key, BigDecimal.valueOf(value).multiply(factor)));
            List<String> targets = "city".equals(measure.scope())
                    ? List.copyOf(after.keySet()) : List.of(selection.districtId());
            for (String target : targets) {
                realized.forEach((key, value) -> after.get(target).merge(key, value, BigDecimal::add));
            }
            BigDecimal contribution = realized.entrySet().stream()
                    .map(entry -> entry.getValue().multiply(SimulationRules.METRIC_WEIGHTS.get(entry.getKey())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            effects.add(new MeasureEffect(measure.id(), measure.name(), measure.categoryId(), measure.scope(),
                    selection.districtId(), measure.cost(), measure.lagQuarters(), factor, targets,
                    immutable(realized), contribution));
        }

        List<AppliedSynergy> synergies = new ArrayList<>();
        for (var rule : SimulationRules.SYNERGIES) {
            if (byId.containsKey(rule.districtMeasureId()) && byId.containsKey(rule.cityMeasureId())) {
                String districtId = byId.get(rule.districtMeasureId()).districtId();
                BigDecimal bonus = BigDecimal.valueOf(rule.bonus());
                after.get(districtId).merge(rule.metric(), bonus, BigDecimal::add);
                synergies.add(new AppliedSynergy(List.of(rule.districtMeasureId(), rule.cityMeasureId()),
                        districtId, rule.metric(), bonus));
            }
        }
        // Clip only after summing every effect and synergy: order must never affect the result.
        after.values().forEach(metrics -> metrics.replaceAll((key, value) -> clip(value)));

        List<DistrictResult> districts = new ArrayList<>();
        for (DistrictResponse district : catalog.getDistricts()) {
            Map<String, BigDecimal> oldMetrics = before.get(district.id());
            Map<String, BigDecimal> newMetrics = after.get(district.id());
            Map<String, BigDecimal> deltas = new LinkedHashMap<>();
            newMetrics.forEach((key, value) -> deltas.put(key, value.subtract(oldMetrics.get(key))));
            BigDecimal oldScore = districtScore(oldMetrics);
            BigDecimal newScore = districtScore(newMetrics);
            districts.add(new DistrictResult(district.id(), district.name(), BigDecimal.valueOf(district.populationShare()),
                    immutable(oldMetrics), immutable(newMetrics), immutable(deltas), oldScore, newScore,
                    newScore.subtract(oldScore), district.dataProvenance()));
        }
        return new Calculation(summarize(districts, true), summarize(districts, false), List.copyOf(districts),
                List.copyOf(effects), List.copyOf(synergies), selected.stream().mapToInt(s -> s.measure().cost()).sum());
    }

    static BigDecimal clip(BigDecimal value) {
        return value.max(BigDecimal.ZERO).min(BigDecimal.valueOf(100));
    }

    private BigDecimal districtScore(Map<String, BigDecimal> metrics) {
        return SimulationRules.METRIC_WEIGHTS.entrySet().stream()
                .map(entry -> metrics.get(entry.getKey()).multiply(entry.getValue()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private ScoreSummary summarize(List<DistrictResult> districts, boolean baseline) {
        BigDecimal average = BigDecimal.ZERO;
        List<CriticalMetric> critical = new ArrayList<>();
        for (DistrictResult district : districts) {
            average = average.add(district.populationShare().multiply(baseline ? district.scoreBefore() : district.scoreAfter()));
            (baseline ? district.before() : district.after()).forEach((key, value) -> {
                if (value.compareTo(SimulationRules.CRITICAL_THRESHOLD) < 0) {
                    critical.add(new CriticalMetric(district.id(), district.name(), key, value));
                }
            });
        }
        DistrictResult weakest = districts.stream().min(Comparator.comparing(
                d -> baseline ? d.scoreBefore() : d.scoreAfter())).orElseThrow();
        BigDecimal minimum = baseline ? weakest.scoreBefore() : weakest.scoreAfter();
        BigDecimal cityTerm = average.multiply(SimulationRules.CITY_WEIGHT);
        BigDecimal weakestTerm = minimum.multiply(SimulationRules.WEAKEST_WEIGHT);
        BigDecimal penalty = BigDecimal.valueOf(critical.size());
        return new ScoreSummary(average, minimum, weakest.id(), weakest.name(), critical.size(), List.copyOf(critical),
                cityTerm, weakestTerm, penalty, cityTerm.add(weakestTerm).subtract(penalty));
    }

    private static <K, V> Map<K, V> immutable(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    record Calculation(ScoreSummary baseline, ScoreSummary summary, List<DistrictResult> districts,
                       List<MeasureEffect> effects, List<AppliedSynergy> synergies, int spent) { }
}
