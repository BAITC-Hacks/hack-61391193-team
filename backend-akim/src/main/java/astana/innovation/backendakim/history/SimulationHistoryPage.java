package astana.innovation.backendakim.history;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SimulationHistoryPage(
        List<Item> items,
        int limit,
        long offset,
        @Schema(description = "Есть ещё записи после этой страницы") boolean hasMore) {
    @Schema(name = "SimulationHistoryItem")
    public record Item(UUID id, Instant createdAt, String modelVersion,
                       BigDecimal finalScore, BigDecimal baselineScore, BigDecimal scoreDelta,
                       int budgetSpent) { }
}
