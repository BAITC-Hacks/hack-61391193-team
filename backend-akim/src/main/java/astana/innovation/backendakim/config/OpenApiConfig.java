package astana.innovation.backendakim.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI simulationOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Аким на 5 часов — Astana Quality of Life Score")
                .version("v1")
                .description("Синтетическая модель хакатона: выберите ровно 5 мероприятий на бюджет до 100. "
                        + "Расчёт детерминированный, объяснение формируется без внешнего LLM. "
                        + "Откройте POST /api/simulation/calculate, нажмите Try it out и Execute: "
                        + "пример уже заполнен. Score примера = 56.54307, базовый = 52.55768."));
    }
}
