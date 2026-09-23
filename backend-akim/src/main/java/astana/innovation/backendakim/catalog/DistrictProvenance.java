package astana.innovation.backendakim.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/** Verified contextual facts are separate from the synthetic 0..100 simulation indices. */
public record DistrictProvenance(
        String modelVersion,
        @Schema(description = "true: показатели T1–C2 — синтетические индексы модели, не официальная статистика")
        boolean syntheticMetrics,
        String metricsMethod,
        String populationShareMethod,
        Map<String, String> metricAssumptions,
        List<Fact> facts,
        List<Source> sources) {
    public record Fact(String key, String value, String unit, String asOf, String sourceId) { }
    public record Source(String id, String title, String url, String asOf) { }
}
