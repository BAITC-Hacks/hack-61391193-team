package astana.innovation.backendakim.simulation;

import astana.innovation.backendakim.catalog.CatalogService;
import tools.jackson.databind.ObjectMapper;

/** Real calculator components shared by tests; the exhaustive optimizer result is cached per JVM. */
final class SimulationFixtures {
    static final ObjectMapper JSON = new ObjectMapper();
    static final CatalogService CATALOG = new CatalogService();
    static final SimulationValidator VALIDATOR = new SimulationValidator(CATALOG);
    static final ScoreCalculator CALCULATOR = new ScoreCalculator(CATALOG);
    static final SimulationOptimizer OPTIMIZER = new SimulationOptimizer(CATALOG, VALIDATOR, CALCULATOR);

    private SimulationFixtures() { }

    static SimulationService service(SimulationLlmClient llm) {
        return new SimulationService(VALIDATOR, CALCULATOR, new SimulationExplanationService(), OPTIMIZER, llm);
    }

    /** The deterministic result exactly as the LLM client receives it. */
    static SimulationResult templateResult(SimulationRequest request) {
        try (var disabled = new SimulationLlmClient(JSON, "", "", "", 1000)) {
            return service(disabled).calculate(request);
        }
    }

    static SimulationRequest request(String json) {
        return JSON.readValue(json, SimulationRequest.class);
    }

    static SimulationRequest example() {
        return request(SimulationRequest.EXAMPLE_JSON);
    }
}
