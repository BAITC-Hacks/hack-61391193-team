package astana.innovation.backendakim.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 254)
        @Schema(description = "Email; пробелы по краям удаляются, регистр не учитывается", example = "user@example.com")
        String email,
        @NotBlank @Size(min = 8, max = 72)
        @Schema(description = "От 8 символов до 72 байт UTF-8", format = "password", example = "MyPassword123")
        String password,
        @NotBlank @Size(min = 3, max = 50)
        @Schema(description = "Отображаемое имя пользователя", example = "Ayanat")
        String username) {

    public RegisterRequest {
        email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
        username = username == null ? null : username.strip();
    }

    @Override
    public String toString() {
        return "RegisterRequest[email=" + email + ", password=[REDACTED], username=" + username + "]";
    }
}
