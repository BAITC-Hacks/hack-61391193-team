package astana.innovation.backendakim.simulation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/simulation", "/api/v1/simulation"})
@Tag(name = "Simulation", description = "Расчёт Astana Quality of Life Score и объяснение результата")
public class SimulationController {
    private final SimulationService simulation;

    public SimulationController(SimulationService simulation) { this.simulation = simulation; }

    @PostMapping("/calculate")
    @Operation(summary = "Рассчитать Score для 5 мероприятий",
            description = "Проверяет бюджет, повторы, лимит направления, районы и конфликты. "
                    + "Применяет lag, synergy и clip, находит глобально лучший набор и сравнивает его с выбором пользователя. "
                    + "При настроенном LLM передаёт оба рассчитанных результата для объяснения; иначе использует шаблон.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(schema = @Schema(implementation = SimulationRequest.class),
                            examples = {
                                    @ExampleObject(name = "Пример v2-saraishyk: 95 единиц → 56.31781049",
                                            value = SimulationRequest.EXAMPLE_JSON),
                                    @ExampleObject(name = "Школа в Сарайшыке: 95 единиц → 54.95216719",
                                            value = SimulationRequest.SARAISHYK_EXAMPLE_JSON)})))
    @ApiResponse(responseCode = "200", description = "Результат и объяснение",
            content = @Content(schema = @Schema(implementation = SimulationResult.class)))
    @ApiResponse(responseCode = "422", description = "Невалидный набор: errors содержит коды и причины; Score не рассчитывается",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ValidationProblem.class)))
    @ApiResponse(responseCode = "400", description = "Некорректный JSON или отсутствует тело",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public SimulationResult calculate(@RequestBody SimulationRequest request) {
        return simulation.calculate(request);
    }

    @GetMapping("/baseline")
    @Operation(summary = "Базовые показатели и Score без действий", description = "Score модели v2-saraishyk = 52.33242049. Это справочный расчёт, не сценарий из 5 решений.")
    public SimulationResult baseline() { return simulation.baseline(); }

    public record ValidationProblem(String type, String title, int status, String detail, String instance,
                                    java.util.List<SimulationValidationException.Violation> errors) { }
}
