package astana.innovation.backendakim.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin", description = "Доступ только для аккаунтов с ролью ADMIN")
@SecurityRequirement(name = "BearerAuth")
public class AdminController {
    private final ObjectProvider<AuthService> auth;
    public AdminController(ObjectProvider<AuthService> auth) { this.auth = auth; }

    @GetMapping("/me")
    @Operation(summary = "Проверить вход в админку и получить профиль администратора")
    @ApiResponse(responseCode = "200", description = "Текущий администратор",
            content = @Content(schema = @Schema(implementation = AuthUserResponse.class)))
    @ApiResponse(responseCode = "401", description = "Требуется действующий JWT", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Аккаунт не является администратором", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Хранилище недоступно или профиль postgres выключен", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<AuthUserResponse> me(@Parameter(hidden = true) Authentication authentication) {
        var service = AuthController.requireStorage(auth);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.currentUser(AuthController.userId(authentication)));
    }
}
