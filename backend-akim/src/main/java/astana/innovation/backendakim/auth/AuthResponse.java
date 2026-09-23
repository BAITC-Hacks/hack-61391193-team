package astana.innovation.backendakim.auth;

import io.swagger.v3.oas.annotations.media.Schema;

public record AuthResponse(
        @Schema(description = "JWT для Authorization: Bearer <accessToken>") String accessToken,
        @Schema(example = "Bearer") String tokenType,
        @Schema(description = "Срок действия токена в секундах", example = "3600") long expiresIn,
        AuthUserResponse user) {

    @Override
    public String toString() {
        return "AuthResponse[accessToken=[REDACTED], tokenType=" + tokenType
                + ", expiresIn=" + expiresIn + ", user=" + user + "]";
    }
}
