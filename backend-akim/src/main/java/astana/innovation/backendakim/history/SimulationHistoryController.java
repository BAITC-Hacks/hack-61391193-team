package astana.innovation.backendakim.history;

import astana.innovation.backendakim.simulation.SimulationController.ValidationProblem;
import astana.innovation.backendakim.auth.AuthController;
import astana.innovation.backendakim.simulation.SimulationRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "History", description = "Сохранение и история сценариев текущего пользователя. Требуется профиль postgres.")
@SecurityScheme(name = "AnonymousBearer", type = SecuritySchemeType.HTTP, scheme = "bearer",
        description = "Старые анонимные токены только для истории. Для аккаунта используйте JWT в BearerAuth.")
@ApiResponse(responseCode = "503", description = "Хранилище недоступно или профиль postgres выключен",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class SimulationHistoryController {
    private final ObjectProvider<SimulationHistoryService> history;

    public SimulationHistoryController(ObjectProvider<SimulationHistoryService> history) { this.history = history; }

    @PostMapping("/users/anonymous")
    @Operation(summary = "Создать анонимный профиль для истории (совместимость)", deprecated = true,
            description = "Без тела запроса. Сохраните accessToken на клиенте: он возвращается один раз. "
                    + "Один профиль создаётся один раз для браузера; повторный POST создаст другой профиль. "
                    + "Токен бессрочный. Для новых пользователей используйте /api/v1/auth/register и JWT.")
    @ApiResponse(responseCode = "201", description = "Профиль сохранён, токен выдан",
            content = @Content(schema = @Schema(implementation = AnonymousUserResponse.class)))
    public ResponseEntity<AnonymousUserResponse> createUser() {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(storage().createAnonymousUser());
    }

    @PostMapping("/simulations")
    @SecurityRequirement(name = "BearerAuth")
    @SecurityRequirement(name = "AnonymousBearer")
    @Operation(summary = "Рассчитать и сохранить сценарий",
            description = "Валидирует 5 решений, рассчитывает Score и сохраняет исходный выбор и полный результат. "
                    + "201 возвращается после завершения транзакции. Каждый успешный POST создаёт новую запись.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(schema = @Schema(implementation = SimulationRequest.class),
                            examples = @ExampleObject(value = SimulationRequest.EXAMPLE_JSON))))
    @ApiResponse(responseCode = "201", description = "Сценарий сохранён. Location указывает маршрут чтения",
            headers = @Header(name = "Location", description = "Маршрут чтения сохранённого сценария",
                    schema = @Schema(type = "string")),
            content = @Content(schema = @Schema(implementation = SavedSimulationResponse.class)))
    @ApiResponse(responseCode = "400", description = "Некорректный JSON или отсутствует тело", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Токен отсутствует или недействителен", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "Невалидный сценарий; запись не создаётся", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ValidationProblem.class)))
    public ResponseEntity<SavedSimulationResponse> save(
            @Parameter(hidden = true) Authentication authentication,
            @RequestBody SimulationRequest request) {
        var saved = storage().save(AuthController.userId(authentication), request);
        return ResponseEntity.created(URI.create("/api/v1/simulations/" + saved.id()))
                .cacheControl(CacheControl.noStore()).body(saved);
    }

    @GetMapping("/simulations")
    @SecurityRequirement(name = "BearerAuth")
    @SecurityRequirement(name = "AnonymousBearer")
    @Operation(summary = "История текущего профиля",
            description = "Краткие результаты, сначала новые: createdAt DESC, id DESC. "
                    + "Параметр userId не принимается: владелец определяется по токену.")
    @ApiResponse(responseCode = "200", description = "Страница истории, в том числе пустая",
            content = @Content(schema = @Schema(implementation = SimulationHistoryPage.class)))
    @ApiResponse(responseCode = "400", description = "Некорректные limit или offset", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Токен отсутствует или недействителен", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<SimulationHistoryPage> list(
            @Parameter(hidden = true) Authentication authentication,
            @Parameter(schema = @Schema(minimum = "1", maximum = "100", defaultValue = "20"))
            @RequestParam(defaultValue = "20") int limit,
            @Parameter(schema = @Schema(minimum = "0", defaultValue = "0"))
            @RequestParam(defaultValue = "0") long offset) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(storage().list(AuthController.userId(authentication), limit, offset));
    }

    @GetMapping("/simulations/{id}")
    @SecurityRequirement(name = "BearerAuth")
    @SecurityRequirement(name = "AnonymousBearer")
    @Operation(summary = "Прочитать сохранённый сценарий",
            description = "Возвращает исходные решения и сохранённый результат без нового расчёта. "
                    + "Чужой и отсутствующий сценарии одинаково возвращают 404.")
    @ApiResponse(responseCode = "200", description = "Сохранённый сценарий",
            content = @Content(schema = @Schema(implementation = SavedSimulationResponse.class)))
    @ApiResponse(responseCode = "400", description = "id должен быть UUID", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Токен отсутствует или недействителен", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Сценарий не найден в текущем профиле", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<SavedSimulationResponse> find(
            @Parameter(hidden = true) Authentication authentication,
            @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(storage().find(AuthController.userId(authentication), id));
    }

    private SimulationHistoryService storage() {
        var service = history.getIfAvailable();
        if (service == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Сохранение и история доступны при запуске backend с профилем postgres.");
        }
        return service;
    }
}
