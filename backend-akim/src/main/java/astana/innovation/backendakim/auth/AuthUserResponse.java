package astana.innovation.backendakim.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record AuthUserResponse(
        UUID id,
        @Schema(example = "user@example.com") String email,
        @Schema(example = "Ayanat") String username,
        @Schema(allowableValues = {"USER", "ADMIN"}) String role,
        Instant createdAt) {
}
