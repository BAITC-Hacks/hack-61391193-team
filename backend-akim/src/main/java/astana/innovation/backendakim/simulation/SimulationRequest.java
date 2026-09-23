package astana.innovation.backendakim.simulation;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record SimulationRequest(
        @ArraySchema(minItems = 5, maxItems = 5, schema = @Schema(implementation = Decision.class),
                arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
        List<Decision> decisions) {

    public record Decision(
            @Schema(description = "Уникальный ID из GET /api/v1/measures", example = "M7",
                    requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^M([1-9]|1[0-4])$")
            String measureId,
            @Schema(description = "Обязателен для районных мер. Для городских отсутствует или null.",
                    allowableValues = {"esil", "almaty", "saryarka", "baikonur", "nura"}, example = "nura")
            String districtId) {
    }

    public static final String EXAMPLE_JSON = """
            {"decisions":[
              {"measureId":"M7","districtId":"nura"},
              {"measureId":"M8","districtId":"nura"},
              {"measureId":"M10","districtId":"nura"},
              {"measureId":"M12"},
              {"measureId":"M5","districtId":"saryarka"}
            ]}
            """;
}
