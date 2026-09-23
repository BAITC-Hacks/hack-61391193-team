package astana.innovation.backendakim.history;

import astana.innovation.backendakim.simulation.SimulationRequest;
import astana.innovation.backendakim.simulation.SimulationResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record SavedSimulationResponse(
        UUID id,
        UUID userId,
        Instant createdAt,
        @Schema(description = "Исходный выбор пользователя: мероприятия и районы в порядке запроса")
        SimulationRequest request,
        @Schema(description = "Полный результат на момент сохранения. При чтении не пересчитывается.")
        SimulationResult result) { }
