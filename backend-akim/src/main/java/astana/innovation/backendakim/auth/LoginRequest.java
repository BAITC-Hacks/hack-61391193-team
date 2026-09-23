package astana.innovation.backendakim.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

public record LoginRequest(
        @NotBlank @Email @Size(max = 254)
        @Schema(example = "admin@example.com") String email,
        @NotBlank @Size(max = 72)
        @Schema(description = "Пароль без удаления пробелов", format = "password", example = "Admin") String password) {

    public LoginRequest {
        email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=[REDACTED]]";
    }
}
