package astana.innovation.backendakim.simulation;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record SimulationResult(
        String metricName,
        String modelVersion,
        @Schema(description = "Точный Score без промежуточного округления", example = "56.54307") BigDecimal finalScore,
        @Schema(description = "Score, округлённый до 2 знаков для показа", example = "56.54") BigDecimal displayScore,
        BigDecimal baselineScore,
        BigDecimal scoreDelta,
        Budget budget,
        int horizonQuarters,
        ScoreSummary baseline,
        ScoreSummary summary,
        List<DistrictResult> districts,
        List<MeasureEffect> measureEffects,
        List<AppliedSynergy> synergies,
        Explanation explanation) {

    public record Budget(int limit, int spent, int remaining) { }

    public record ScoreSummary(
            BigDecimal dAvg, BigDecimal dMin, String weakestDistrictId, String weakestDistrictName,
            int nCrit, List<CriticalMetric> criticalMetrics,
            BigDecimal cityContribution, BigDecimal weakestDistrictContribution,
            BigDecimal criticalPenalty, BigDecimal finalScore) { }

    public record CriticalMetric(String districtId, String districtName, String metric, BigDecimal value) { }

    public record DistrictResult(
            String id, String name, BigDecimal populationShare,
            Map<String, BigDecimal> before, Map<String, BigDecimal> after, Map<String, BigDecimal> metricDeltas,
            BigDecimal scoreBefore, BigDecimal scoreAfter, BigDecimal scoreDelta) { }

    public record MeasureEffect(
            String measureId, String name, String categoryId, String scope, String targetDistrictId,
            int cost, int lagQuarters, BigDecimal realizationFactor, List<String> affectedDistrictIds,
            @Schema(description = "Эффекты в каждом затронутом районе с учётом лага, до clip и синергий")
            Map<String, BigDecimal> realizedEffects,
            @Schema(description = "Прямой вклад в D района до clip и синергий; не вклад в нелинейный FINAL SCORE")
            BigDecimal districtScoreContributionBeforeClip) { }

    public record AppliedSynergy(
            List<String> measureIds, String districtId, String metric, BigDecimal bonus) { }

    public record Explanation(
            @Schema(description = "template: детерминированное объяснение без вызова LLM") String source,
            String summary, List<String> strengths, List<String> risks, List<String> recommendations) { }
}
