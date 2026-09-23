package astana.innovation.backendakim.simulation;

import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static astana.innovation.backendakim.simulation.SimulationResult.*;

/** A transparent template fallback. It describes calculator output and never invents a score. */
@Service
public class SimulationExplanationService {
    Explanation explain(ScoreCalculator.Calculation calculation) {
        var baseline = calculation.baseline();
        var result = calculation.summary();
        BigDecimal delta = result.finalScore().subtract(baseline.finalScore());
        String summary = "Astana Quality of Life Score: %s (база %s, изменение %s). "
                .formatted(format(result.finalScore()), format(baseline.finalScore()), signed(delta))
                + "Средний балл города: %s. Самый слабый район — %s: %s. Критических показателей: %d → %d. "
                .formatted(format(result.dAvg()), result.weakestDistrictName(), format(result.dMin()), baseline.nCrit(), result.nCrit())
                + "Бюджет: %d из %d, остаток %d; остаток не даёт бонуса."
                .formatted(calculation.spent(), SimulationRules.BUDGET, SimulationRules.BUDGET - calculation.spent());
        List<String> strengths = new ArrayList<>();
        calculation.districts().stream().filter(d -> d.scoreDelta().signum() > 0)
                .sorted(Comparator.comparing(DistrictResult::scoreDelta).reversed())
                .forEach(d -> strengths.add("%s: %s → %s (%s).".formatted(d.name(), format(d.scoreBefore()),
                        format(d.scoreAfter()), signed(d.scoreDelta()))));
        calculation.synergies().forEach(s -> strengths.add("Синергия %s: %s +%s в районе %s; бонус без уменьшения на лаг."
                .formatted(String.join(" + ", s.measureIds()), s.metric(), format(s.bonus()),
                        districtName(calculation, s.districtId()))));
        if (baseline.nCrit() > result.nCrit()) strengths.add("Устранено критических показателей: " + (baseline.nCrit() - result.nCrit()) + ".");

        List<String> risks = new ArrayList<>();
        for (CriticalMetric metric : result.criticalMetrics()) {
            risks.add("%s, %s = %s: ниже 40, штраф 1 балл.".formatted(metric.districtName(), metric.metric(), format(metric.value())));
        }
        calculation.districts().forEach(d -> d.metricDeltas().forEach((metric, value) -> {
            if (value.signum() < 0) risks.add("%s, %s: снижение на %s.".formatted(d.name(), metric, format(value.abs())));
        }));
        if (!calculation.effects().isEmpty()) {
            risks.add("Эффекты учтены на горизонте 8 кварталов: каждая мера реализуется в доле (8 − lag) / 8.");
        }
        risks.add("Это синтетическая модель хакатона, а не прогноз по официальной статистике города.");
        List<String> recommendations = new ArrayList<>();
        if (result.nCrit() > 0) recommendations.add("Сравните набор с мерами для оставшихся показателей ниже 40: каждый такой показатель даёт штраф.");
        recommendations.add("При сравнении сценариев уделите внимание району «%s»: его балл входит в Score с весом 30%%."
                .formatted(result.weakestDistrictName()));
        recommendations.add("Проверяйте замену мероприятий повторным расчётом API; полный эффект из каталога уменьшается с учётом лага.");
        return new Explanation("template", summary, List.copyOf(strengths), List.copyOf(risks), List.copyOf(recommendations));
    }

    private String districtName(ScoreCalculator.Calculation calculation, String id) {
        return calculation.districts().stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow().name();
    }

    private static String signed(BigDecimal value) { return (value.signum() >= 0 ? "+" : "") + format(value); }
    private static String format(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP).toPlainString(); }
}
