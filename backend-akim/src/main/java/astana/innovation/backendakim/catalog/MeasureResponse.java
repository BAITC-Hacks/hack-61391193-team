package astana.innovation.backendakim.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record MeasureResponse(
        String id,
        String categoryId,
        String categoryName,
        String name,
        String scope,
        int cost,
        int lagQuarters,
        double realizationFactor,
        Map<String, Integer> fullEffects,
        Map<String, Double> realizedEffects
) {
    @JsonProperty("contextUrl")
    public String contextUrl() {
        return "M3".equals(id) ? "/api/v1/measures/M3/context" : null;
    }

    @JsonProperty("mapLayerUrl")
    public String mapLayerUrl() {
        return "M3".equals(id) ? "/api/v1/map/lrt" : null;
    }
}
