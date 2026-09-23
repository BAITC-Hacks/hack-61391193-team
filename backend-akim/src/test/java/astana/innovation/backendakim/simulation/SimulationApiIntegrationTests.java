package astana.innovation.backendakim.simulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SimulationApiIntegrationTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @Test
    void calculatesThroughBothRoutes() throws Exception {
        for (String path : new String[]{"/api/simulation/calculate", "/api/v1/simulation/calculate"}) {
            var response = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(SimulationRequest.EXAMPLE_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.modelVersion").value("v2-saraishyk"))
                    .andExpect(jsonPath("$.finalScore").value(56.31781049))
                    .andExpect(jsonPath("$.displayScore").value(56.32))
                    .andExpect(jsonPath("$.baselineScore").value(52.33242049))
                    .andExpect(jsonPath("$.scoreDelta").value(3.98539))
                    .andExpect(jsonPath("$.budget.spent").value(95))
                    .andExpect(jsonPath("$.budget.remaining").value(5))
                    .andExpect(jsonPath("$.summary.nCrit").value(0))
                    .andExpect(jsonPath("$.districts", hasSize(6)))
                    .andExpect(jsonPath("$.measureEffects", hasSize(5)))
                    .andExpect(jsonPath("$.explanation.source").value("template"))
                    .andExpect(jsonPath("$.bestSolution.algorithm").value("exhaustive-search"))
                    .andExpect(jsonPath("$.bestSolution.provenOptimal").value(true))
                    .andExpect(jsonPath("$.bestSolution.evaluatedCandidates", greaterThan(0)))
                    .andExpect(jsonPath("$.bestSolution.decisions", hasSize(5)))
                    .andExpect(jsonPath("$.comparison.isOptimal").value(false))
                    .andReturn();
            var result = json.readValue(response.getResponse().getContentAsString(), SimulationResult.class);
            assertThat(result.bestSolution().finalScore()).isGreaterThan(result.finalScore());
            assertThat(result.comparison().scoreGap())
                    .isEqualByComparingTo(result.bestSolution().finalScore().subtract(result.finalScore()));
        }
    }

    @Test
    void calculatesSnowAndDrainageTogetherWithLocalEffectsAndLag() throws Exception {
        for (String path : new String[]{"/api/simulation/calculate", "/api/v1/simulation/calculate"}) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                            .content(scenario("M15:nura", "M16:nura", "M7:saraishyk", "M10:esil", "M1:almaty")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.budget.spent").value(88))
                    .andExpect(jsonPath("$.budget.remaining").value(12))
                    .andExpect(jsonPath("$.measureEffects[3].measureId").value("M15"))
                    .andExpect(jsonPath("$.measureEffects[3].affectedDistrictIds", org.hamcrest.Matchers.contains("nura")))
                    .andExpect(jsonPath("$.measureEffects[3].realizationFactor").value(0.875))
                    .andExpect(jsonPath("$.measureEffects[3].districtScoreContributionBeforeClip").value(1.155))
                    .andExpect(jsonPath("$.measureEffects[4].measureId").value("M16"))
                    .andExpect(jsonPath("$.measureEffects[4].affectedDistrictIds", org.hamcrest.Matchers.contains("nura")))
                    .andExpect(jsonPath("$.measureEffects[4].realizationFactor").value(0.625))
                    .andExpect(jsonPath("$.measureEffects[4].districtScoreContributionBeforeClip").value(1.1625))
                    .andExpect(jsonPath("$.districts[4].metricDeltas.T1").value(4.5))
                    .andExpect(jsonPath("$.districts[4].metricDeltas.T2").value(2.625))
                    .andExpect(jsonPath("$.districts[4].metricDeltas.B2").value(9.5))
                    .andExpect(jsonPath("$.districts[4].metricDeltas.C1").value(7.5))
                    .andExpect(jsonPath("$.districts[4].scoreDelta").value(2.3175))
                    .andExpect(jsonPath("$.districts[2].scoreDelta").value(0))
                    .andExpect(jsonPath("$.synergies", hasSize(0)));
        }
    }

    @Test
    void resubmittingBestSolutionDecisionsReproducesTheBestScore() throws Exception {
        var first = mvc.perform(post("/api/v1/simulation/calculate").contentType(MediaType.APPLICATION_JSON)
                        .content(SimulationRequest.EXAMPLE_JSON))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // Sent back exactly as the frontend receives it, including "districtId": null for city measures.
        var bestDecisions = json.readTree(first).path("bestSolution").path("decisions");
        var best = json.readTree(first).path("bestSolution");

        mvc.perform(post("/api/v1/simulation/calculate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisions\":" + bestDecisions + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finalScore").value(best.path("finalScore").doubleValue()))
                .andExpect(jsonPath("$.budget.spent").value(best.path("budget").path("spent").intValue()))
                .andExpect(jsonPath("$.comparison.isOptimal").value(true))
                .andExpect(jsonPath("$.comparison.scoreGap").value(0.0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidScenarios")
    void rejectsInvalidScenariosWithoutScore(String code, String json) throws Exception {
        mvc.perform(post("/api/simulation/calculate").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().is(422))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[*].code", hasItem(code)))
                .andExpect(jsonPath("$.finalScore").doesNotExist())
                .andExpect(jsonPath("$.summary").doesNotExist());
    }

    static Stream<Arguments> invalidScenarios() {
        String example = SimulationRequest.EXAMPLE_JSON;
        return Stream.of(
                Arguments.of("DECISION_COUNT", "{}"),
                Arguments.of("DECISION_COUNT", "{\"decisions\":null}"),
                Arguments.of("DECISION_COUNT", "{\"decisions\":[]}"),
                Arguments.of("DECISION_COUNT", "{\"decisions\":[null,null,null,null]}"),
                Arguments.of("DECISION_COUNT", "{\"decisions\":[null,null,null,null,null,null]}"),
                Arguments.of("MEASURE_REQUIRED", example.replace("{\"measureId\":\"M7\",\"districtId\":\"nura\"}", "null")),
                Arguments.of("MEASURE_REQUIRED", example.replace("\"measureId\":\"M7\"", "\"measureId\":null")),
                Arguments.of("MEASURE_REQUIRED", example.replace("\"M7\"", "\"\"")),
                Arguments.of("UNKNOWN_MEASURE", example.replace("\"M7\"", "\"M99\"")),
                Arguments.of("DISTRICT_REQUIRED", example.replace("\"districtId\":\"nura\"", "\"districtId\":null")),
                Arguments.of("DISTRICT_REQUIRED", example.replace("\"nura\"", "\"\"")),
                Arguments.of("UNKNOWN_DISTRICT", example.replace("\"nura\"", "\"unknown\"")),
                Arguments.of("CITY_DISTRICT_FORBIDDEN", example.replace("{\"measureId\":\"M12\"}", "{\"measureId\":\"M12\",\"districtId\":\"nura\"}")),
                Arguments.of("DUPLICATE_MEASURE", example.replace("\"M8\"", "\"M7\"")),
                Arguments.of("BUDGET_EXCEEDED", example.replace("\"M10\"", "\"M3\"")),
                Arguments.of("CATEGORY_LIMIT", example.replace("\"M10\"", "\"M9\"")),
                Arguments.of("DISTRICT_REQUIRED", scenario("M15", "M16:nura", "M1:almaty", "M7:nura", "M10:esil")),
                Arguments.of("DISTRICT_REQUIRED", scenario("M15:nura", "M16", "M1:almaty", "M7:nura", "M10:esil")),
                Arguments.of("UNKNOWN_DISTRICT", scenario("M15:unknown", "M16:nura", "M1:almaty", "M7:nura", "M10:esil")),
                Arguments.of("UNKNOWN_DISTRICT", scenario("M15:nura", "M16:unknown", "M1:almaty", "M7:nura", "M10:esil")),
                Arguments.of("CATEGORY_LIMIT", scenario("M15:nura", "M16:esil", "M12", "M9:nura", "M10:nura")),
                Arguments.of("CONFLICT", scenario("M1:nura", "M3:esil", "M9:nura", "M10:nura", "M12")),
                Arguments.of("CONFLICT", scenario("M4:nura", "M7:nura", "M9:nura", "M10:nura", "M12")),
                Arguments.of("CONFLICT", scenario("M5:nura", "M13:nura", "M9:nura", "M10:nura", "M12")));
    }

    @ParameterizedTest
    @MethodSource("validScenarios")
    void allowsValidBoundaryScenarios(String json, int budget) throws Exception {
        mvc.perform(post("/api/simulation/calculate").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk()).andExpect(jsonPath("$.budget.spent").value(budget));
    }

    static Stream<Arguments> validScenarios() {
        return Stream.of(
                Arguments.of(scenario("M4:esil", "M7:nura", "M9:nura", "M10:nura", "M12"), 75),
                Arguments.of(scenario("M5:saryarka", "M13:almaty", "M9:nura", "M10:nura", "M12"), 89),
                Arguments.of(scenario("M9:nura", "M11:nura", "M10:nura", "M12", "M4:saryarka"), 61),
                Arguments.of(scenario("M7:saraishyk", "M8:nura", "M10:nura", "M12", "M5:saryarka"), 95));
    }

    @Test
    void acceptsBudgetExactlyOneHundred() throws Exception {
        mvc.perform(post("/api/simulation/calculate").contentType(MediaType.APPLICATION_JSON)
                        .content(scenario("M1:nura", "M2", "M7:nura", "M8:nura", "M14")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.budget.spent").value(100))
                .andExpect(jsonPath("$.budget.remaining").value(0));
    }

    @Test
    void malformedJsonIsBadRequest() throws Exception {
        for (String json : new String[]{"{", "null", "{\"decisions\":\"wrong\"}", "{\"decisions\":[1,2,3,4,5]}"}) {
            mvc.perform(post("/api/simulation/calculate").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.finalScore").doesNotExist());
        }
    }

    @Test
    void exposesBaselineSwaggerAndMachineReadableOpenApiWithExample() throws Exception {
        mvc.perform(get("/api/simulation/baseline")).andExpect(status().isOk())
                .andExpect(jsonPath("$.finalScore").value(52.33242049)).andExpect(jsonPath("$.summary.nCrit").value(2))
                .andExpect(jsonPath("$.bestSolution").doesNotExist())
                .andExpect(jsonPath("$.comparison").doesNotExist());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.paths['/api/simulation/calculate'].post.responses['422']").exists())
                .andExpect(jsonPath("$.paths['/api/simulation/calculate'].post.requestBody.content['application/json'].examples").exists())
                .andExpect(jsonPath("$.components.schemas.SimulationResult.properties.finalScore").exists());
        mvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isOk());
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void allowsFrontendPostPreflight() throws Exception {
        mvc.perform(options("/api/simulation/calculate").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    private static String scenario(String... selections) {
        return "{\"decisions\":[" + Stream.of(selections).map(selection -> {
            String[] parts = selection.split(":");
            return "{\"measureId\":\"" + parts[0] + "\"" + (parts.length == 2 ? ",\"districtId\":\"" + parts[1] + "\"" : "") + "}";
        }).collect(java.util.stream.Collectors.joining(",")) + "]}";
    }
}
