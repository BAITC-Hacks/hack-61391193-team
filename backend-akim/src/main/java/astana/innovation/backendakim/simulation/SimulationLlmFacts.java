package astana.innovation.backendakim.simulation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static astana.innovation.backendakim.simulation.SimulationResult.*;

/**
 * The only data the LLM sees: a compact, pre-rounded digest of a calculated result.
 * The same digest is later used to verify every number and measure the model mentions.
 */
record SimulationLlmFacts(Map<String, Object> payload, Set<String> measureIds) {
    static final int TOP_METRIC_GAINS = 3;
    static final String CITY_WIDE = "весь город";

    static SimulationLlmFacts of(SimulationRequest request, SimulationResult result) {
        boolean optimal = result.comparison() == null || result.comparison().isOptimal();
        Set<String> measureIds = new LinkedHashSet<>();
        request.decisions().forEach(d -> measureIds.add(d.measureId()));

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("model", model());
        facts.put("userPlan", plan(request.decisions(), result.measureEffects(), result.districts(),
                result.finalScore(), result.scoreDelta(), result.budget(), result.baseline(), result.summary(),
                result.synergies()));
        if (result.comparison() != null) {
            Map<String, Object> comparison = new LinkedHashMap<>();
            comparison.put("userPlanIsOptimal", optimal);
            comparison.put("scoreGap", round(result.comparison().scoreGap()));
            comparison.put("meaning", optimal
                    ? "Набор пользователя уже даёт максимальный Score этой модели; лучшего набора нет."
                    : "scoreGap = Score лучшего набора − Score пользователя.");
            facts.put("comparison", comparison);
        }
        var best = result.bestSolution();
        if (best != null && !optimal) {
            best.decisions().forEach(d -> measureIds.add(d.measureId()));
            @SuppressWarnings("unchecked")
            var comparison = (Map<String, Object>) facts.get("comparison");
            var userLabels = labels(request.decisions(), result.measureEffects(), result.districts());
            var bestLabels = labels(best.decisions(), best.measureEffects(), result.districts());
            comparison.put("onlyInUserPlan", userLabels.stream().filter(l -> !bestLabels.contains(l)).toList());
            comparison.put("onlyInBestPlan", bestLabels.stream().filter(l -> !userLabels.contains(l)).toList());
            comparison.put("inBothPlans", userLabels.stream().filter(bestLabels::contains).toList());
            comparison.put("onlyInUserPlanCost", cost(result.measureEffects(), bestLabels, result.districts()));
            comparison.put("onlyInBestPlanCost", cost(best.measureEffects(), userLabels, result.districts()));
            comparison.put("weakestDistrictScoreGap", round(best.summary().dMin().subtract(result.summary().dMin())));
            facts.put("bestPlan", plan(best.decisions(), best.measureEffects(), best.districts(),
                    best.finalScore(), best.scoreDelta(), best.budget(), result.baseline(), best.summary(),
                    best.synergies()));
        }
        return new SimulationLlmFacts(facts, Set.copyOf(measureIds));
    }

    /** Total cost of the plan's measures that the other plan does not contain. */
    private static int cost(List<MeasureEffect> effects, List<String> otherLabels, List<DistrictResult> districts) {
        return effects.stream().filter(e -> !otherLabels.contains(label(e, districts))).mapToInt(MeasureEffect::cost).sum();
    }

    private static List<String> labels(List<SimulationRequest.Decision> decisions, List<MeasureEffect> effects,
                                       List<DistrictResult> districts) {
        return decisions.stream().map(d -> label(effects.stream()
                .filter(e -> e.measureId().equals(d.measureId())).findFirst().orElseThrow(), districts)).toList();
    }

    /** "M7 Школа + детсад (модульное строительство) — Нура": the same measure in another district differs. */
    private static String label(MeasureEffect effect, List<DistrictResult> districts) {
        String place = effect.targetDistrictId() == null ? CITY_WIDE : districts.stream()
                .filter(district -> district.id().equals(effect.targetDistrictId())).findFirst().orElseThrow().name();
        return effect.measureId() + " " + effect.name() + " — " + place;
    }

    private static Map<String, Object> model() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("metric", "Astana Quality of Life Score");
        model.put("formula", "Score = 0.7 × средний балл города (по доле населения) + 0.3 × балл слабейшего района"
                + " − 1 за каждый показатель района ниже 40");
        model.put("weights", "70% веса — город в целом, 30% — самый слабый район; штраф 1 балл за каждый показатель ниже 40");
        model.put("budget", SimulationRules.BUDGET);
        model.put("decisions", SimulationRules.DECISIONS);
        model.put("horizonQuarters", SimulationRules.HORIZON);
        model.put("lag", "мера с лагом L кварталов реализует (8 − L)/8 полного эффекта");
        model.put("districtScoreGain", "прирост балла затронутого района от меры с учётом лага, до синергий и"
                + " ограничения 0–100; у городской меры — в каждом из районов");
        model.put("scope", "Учебная синтетическая модель хакатона с фиксированными данными и каталогом;"
                + " не прогноз для реального города.");
        return model;
    }

    private static Map<String, Object> plan(List<SimulationRequest.Decision> decisions, List<MeasureEffect> effects,
                                            List<DistrictResult> districts, BigDecimal score, BigDecimal scoreDelta,
                                            Budget budget, ScoreSummary before, ScoreSummary after,
                                            List<AppliedSynergy> synergies) {
        Map<String, String> names = new LinkedHashMap<>();
        districts.forEach(d -> names.put(d.id(), d.name()));

        List<Map<String, Object>> measures = new ArrayList<>();
        for (var decision : decisions) {
            var effect = effects.stream().filter(e -> e.measureId().equals(decision.measureId())).findFirst().orElseThrow();
            Map<String, Object> measure = new LinkedHashMap<>();
            measure.put("id", effect.measureId());
            measure.put("name", effect.name());
            measure.put("direction", SimulationRules.CATEGORY_NAMES.getOrDefault(effect.categoryId(), effect.categoryId()));
            measure.put("district", effect.targetDistrictId() == null ? CITY_WIDE : names.get(effect.targetDistrictId()));
            measure.put("cost", effect.cost());
            measure.put("lagQuarters", effect.lagQuarters());
            measure.put("realizedShare", (SimulationRules.HORIZON - effect.lagQuarters()) + "/" + SimulationRules.HORIZON);
            measure.put("districtScoreGain", round(effect.districtScoreContributionBeforeClip()));
            measures.add(measure);
        }

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("measures", measures);
        plan.put("score", round(score));
        plan.put("scoreChangeFromBaseline", round(scoreDelta));
        plan.put("baselineScore", round(before.finalScore()));
        plan.put("budgetSpent", budget.spent());
        plan.put("budgetRemaining", budget.remaining());
        plan.put("cityAverage", round(after.dAvg()));
        Map<String, Object> weakest = new LinkedHashMap<>();
        weakest.put("name", after.weakestDistrictName());
        weakest.put("score", round(after.dMin()));
        plan.put("weakestDistrict", weakest);
        plan.put("criticalMetricsBefore", before.nCrit());
        plan.put("criticalMetricsAfter", after.nCrit());
        plan.put("remainingCriticalMetrics", after.criticalMetrics().stream()
                .map(m -> metric(m.districtName(), m.metric(), "before", round(districts.stream()
                                .filter(d -> d.id().equals(m.districtId())).findFirst().orElseThrow().before().get(m.metric())),
                        "value", round(m.value()))).toList());
        plan.put("districts", districts.stream()
                .sorted(Comparator.comparing(DistrictResult::scoreDelta).reversed())
                .map(d -> {
                    Map<String, Object> district = new LinkedHashMap<>();
                    district.put("name", d.name());
                    district.put("before", round(d.scoreBefore()));
                    district.put("after", round(d.scoreAfter()));
                    district.put("change", round(d.scoreDelta()));
                    return district;
                }).toList());
        plan.put("largestMetricGains", metricChanges(districts, 1).stream().limit(TOP_METRIC_GAINS).toList());
        plan.put("metricDeclines", declines(districts, effects));
        plan.put("synergies", synergies.stream().map(s -> metric(names.get(s.districtId()), s.metric(),
                "measures", String.join(" + ", s.measureIds()), "bonus", round(s.bonus()))).toList());
        return plan;
    }

    /** Every metric that went down, with the measures that caused it and whether it fell below the threshold. */
    private static List<Map<String, Object>> declines(List<DistrictResult> districts, List<MeasureEffect> effects) {
        List<Map<String, Object>> declines = new ArrayList<>();
        for (var d : districts) {
            d.metricDeltas().forEach((metric, delta) -> {
                if (delta.signum() >= 0) return;
                var causedBy = effects.stream()
                        .filter(e -> e.affectedDistrictIds().contains(d.id()))
                        .filter(e -> e.realizedEffects().getOrDefault(metric, BigDecimal.ZERO).signum() < 0)
                        .map(e -> e.measureId() + " " + e.name()).toList();
                BigDecimal before = d.before().get(metric), after = d.after().get(metric);
                boolean newlyCritical = before.compareTo(SimulationRules.CRITICAL_THRESHOLD) >= 0
                        && after.compareTo(SimulationRules.CRITICAL_THRESHOLD) < 0;
                declines.add(metric(d.name(), metric, "before", round(before), "after", round(after),
                        "change", round(delta), "causedBy", causedBy, "fellBelow40", newlyCritical));
            });
        }
        return declines;
    }

    /** Metric deltas with the given sign, largest magnitude first. */
    private static List<Map<String, Object>> metricChanges(List<DistrictResult> districts, int sign) {
        record Change(DistrictResult district, String metric, BigDecimal delta) { }
        List<Change> changes = new ArrayList<>();
        districts.forEach(d -> d.metricDeltas().forEach((metric, delta) -> {
            if (delta.signum() == sign) changes.add(new Change(d, metric, delta));
        }));
        return changes.stream()
                .sorted(Comparator.comparing((Change c) -> c.delta().abs()).reversed())
                .map(c -> metric(c.district().name(), c.metric(),
                        "before", round(c.district().before().get(c.metric())),
                        "after", round(c.district().after().get(c.metric())),
                        "change", round(c.delta())))
                .toList();
    }

    private static Map<String, Object> metric(String district, String code, Object... values) {
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("district", district);
        metric.put("metric", code + " " + SimulationRules.METRIC_NAMES.getOrDefault(code, ""));
        for (int i = 0; i < values.length; i += 2) metric.put((String) values[i], values[i + 1]);
        return metric;
    }

    /** Two decimals, no trailing zeros, never in exponent form. */
    static BigDecimal round(BigDecimal value) {
        return new BigDecimal(value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
    }
}
