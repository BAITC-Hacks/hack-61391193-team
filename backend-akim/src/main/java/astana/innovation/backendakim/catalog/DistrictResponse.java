package astana.innovation.backendakim.catalog;

import java.util.Map;

public record DistrictResponse(
        String id,
        String name,
        double populationShare,
        Map<String, Integer> metrics,
        double baselineScore,
        DistrictProvenance dataProvenance
) {
    public DistrictResponse(String id, String name, double populationShare,
                            Map<String, Integer> metrics, double baselineScore) {
        this(id, name, populationShare, metrics, baselineScore, null);
    }
}
