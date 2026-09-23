package astana.innovation.backendakim.simulation;

import java.util.List;
import java.util.Map;
import java.math.BigDecimal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/simulation")
public class SimulationBootstrapController {

    private final SimulationService simulation;

    public SimulationBootstrapController(SimulationService simulation) {
        this.simulation = simulation;
    }

    @GetMapping("/bootstrap")
    public BootstrapResponse bootstrap() {
        return new BootstrapResponse(
                "v1",
                SimulationRules.BUDGET,
                SimulationRules.DECISIONS,
                SimulationRules.HORIZON,
                SimulationRules.MAX_PER_CATEGORY,
                simulation.baseline().displayScore().doubleValue(),
                new ApiLinks(
                        "/api/v1/districts",
                        "/api/v1/districts/{districtId}",
                        "/api/v1/measures",
                        "/api/v1/districts/{districtId}/measures",
                        "/api/v1/map/layers",
                        "/api/v1/simulation/calculate",
                        "/api/v1/simulation/baseline",
                        "/swagger-ui.html",
                        "/v3/api-docs"),
                new MapLinks(
                        "/api/v1/map/city-boundary",
                        "/api/v1/map/districts",
                        "/api/v1/map/district-stats",
                        "/api/v1/map/pois",
                        "/api/v1/map/parks",
                        "/api/v1/map/roads"),
                List.of(
                        "Score рассчитывается по синтетическому датасету хакатона.",
                        "Данные Overture используются как географический контекст и не являются официальной статистикой."),
                new ScoreRules(SimulationRules.FORMULA, SimulationRules.METRIC_WEIGHTS,
                        SimulationRules.CRITICAL_THRESHOLD, SimulationRules.SYNERGIES, SimulationRules.CONFLICTS));
    }

    public record BootstrapResponse(
            String apiVersion,
            int budgetLimit,
            int requiredDecisionCount,
            int horizonQuarters,
            int maxMeasuresPerCategory,
            double baselineScore,
            ApiLinks api,
            MapLinks map,
            List<String> dataNotes,
            ScoreRules scoreRules) {
    }

    public record ApiLinks(
            String districts,
            String districtTemplate,
            String measures,
            String districtMeasuresTemplate,
            String mapLayers,
            String calculate,
            String baseline,
            String swaggerUi,
            String openApi) {
    }

    public record ScoreRules(String formula, Map<String, BigDecimal> metricWeights,
                             BigDecimal criticalThresholdExclusive,
                             List<SimulationRules.SynergyRule> synergies,
                             List<SimulationRules.ConflictRule> conflicts) { }

    public record MapLinks(
            String cityBoundary,
            String districts,
            String districtStats,
            String pois,
            String parks,
            String roads) {
    }
}
