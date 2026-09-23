package astana.innovation.backendakim.history;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record AnonymousUserResponse(
        UUID userId,
        @Schema(description = "Секретный токен профиля. Возвращается только при создании; сохраните на клиенте.")
        String accessToken,
        @Schema(example = "Bearer") String tokenType,
        Instant createdAt) {
    @Override
    public String toString() {
        return "AnonymousUserResponse[userId=" + userId + ", accessToken=[REDACTED], tokenType="
                + tokenType + ", createdAt=" + createdAt + "]";
    }
}
