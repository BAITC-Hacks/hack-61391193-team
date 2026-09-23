package astana.innovation.backendakim.catalog;

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
}
