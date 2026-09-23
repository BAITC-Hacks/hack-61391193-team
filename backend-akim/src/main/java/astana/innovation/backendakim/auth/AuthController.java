package astana.innovation.backendakim.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Регистрация, вход и текущий аккаунт. Требуется профиль postgres.")
@SecurityScheme(name = "BearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT",
        description = "Получите accessToken через /api/v1/auth/login или /register и вставьте без префикса Bearer.")
@ApiResponse(responseCode = "503", description = "База данных недоступна или профиль postgres выключен", content = @Content(
        mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class AuthController {
    private final ObjectProvider<AuthService> auth;
    public AuthController(ObjectProvider<AuthService> auth) { this.auth = auth; }

    @PostMapping("/register")
    @Operation(summary = "Зарегистрировать пользователя и получить JWT",
            description = "Email уникален без учёта регистра. Новый аккаунт всегда имеет роль USER.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
                    schema = @Schema(implementation = RegisterRequest.class), examples = @ExampleObject(value = """
                    {"email":"user@example.com","password":"Password123!","username":"user"}
                    """))))
    @ApiResponse(responseCode = "201", description = "Аккаунт сохранён; JWT готов к использованию",
            content = @Content(schema = @Schema(implementation = AuthResponse.class)))
    @ApiResponse(responseCode = "400", description = "Некорректные поля", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Email уже занят или зарезервирован", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(storage().register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "Войти по email и паролю",
            description = "Выдаёт JWT с ограниченным сроком действия. Email или пароль неверен — одинаковый ответ 401.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
                    schema = @Schema(implementation = LoginRequest.class), examples = @ExampleObject(value = """
                    {"email":"admin@example.com","password":"Admin"}
                    """))))
    @ApiResponse(responseCode = "200", description = "JWT и профиль пользователя",
            content = @Content(schema = @Schema(implementation = AuthResponse.class)))
    @ApiResponse(responseCode = "400", description = "Некорректное тело запроса", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Неверный email или пароль", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(storage().login(request));
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "BearerAuth")
    @Operation(summary = "Получить текущий аккаунт по JWT")
    @ApiResponse(responseCode = "200", description = "Публичные поля профиля, без пароля",
            content = @Content(schema = @Schema(implementation = AuthUserResponse.class)))
    @ApiResponse(responseCode = "401", description = "JWT отсутствует, недействителен или истёк", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Анонимный профиль не является зарегистрированным аккаунтом", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<AuthUserResponse> me(@Parameter(hidden = true) Authentication authentication) {
        AuthService service = storage();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.currentUser(userId(authentication)));
    }

    private AuthService storage() {
        return requireStorage(auth);
    }

    static AuthService requireStorage(ObjectProvider<AuthService> provider) {
        AuthService service = provider.getIfAvailable();
        if (service == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Регистрация и вход доступны при запуске backend с профилем postgres.");
        }
        return service;
    }

    public static UUID userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Требуется действующий токен доступа.");
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Требуется действующий токен доступа.");
        }
    }
}
