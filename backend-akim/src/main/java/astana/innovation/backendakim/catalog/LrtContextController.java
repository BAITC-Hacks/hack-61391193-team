package astana.innovation.backendakim.catalog;

import io.swagger.v3.oas.annotations.Operation;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** A bundled, dated reference snapshot; it is never an input to the Score calculation. */
@RestController
public class LrtContextController {
    private final Map<String, Object> context;

    public LrtContextController(ObjectMapper json) throws IOException {
        var resource = new ClassPathResource("measure-data/M3/lrt-context.json");
        context = json.readValue(resource.getContentAsByteArray(), new TypeReference<>() { });
    }

    @Operation(summary = "Официальные сведения о ЛРТ Астаны для M3",
            description = "Справочный снимок с датами и источниками. Геометрия маршрута доступна "
                    + "отдельно в /api/v1/map/lrt; покрытие неполное. Эти данные не меняют Score.")
    @GetMapping(value = "/api/v1/measures/M3/context", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getContext() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(context);
    }
}
