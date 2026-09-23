package astana.innovation.backendakim;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LrtDataIntegrationTests {

    private static final MediaType GEO_JSON = MediaType.parseMediaType("application/geo+json");
    private static final String CONTEXT_URL = "/api/v1/measures/M3/context";
    private static final String MAP_URL = "/api/v1/map/lrt";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper json;

    @Test
    void returnsSourcedLrtFactsWithoutChangingSimulationScoring() throws Exception {
        var response = mockMvc.perform(get(CONTEXT_URL))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.measureId").value("M3"))
                .andExpect(jsonPath("$.verifiedAt").value("2026-09-23"))
                .andExpect(jsonPath("$.map.url").value(MAP_URL))
                .andExpect(jsonPath("$.map.coverage").value("partial"))
                .andExpect(jsonPath("$.simulationUsage.affectsScore").value(false))
                .andReturn();
        JsonNode context = json.readTree(response.getResponse().getContentAsString());
        LocalDate verifiedAt = LocalDate.parse(context.path("verifiedAt").asText());

        Set<String> sourceIds = new HashSet<>();
        assertThat(context.path("sources").size()).isPositive();
        for (JsonNode source : context.path("sources")) {
            String id = source.path("id").asText();
            assertThat(id).isNotBlank();
            assertThat(sourceIds.add(id)).as("unique source id: %s", id).isTrue();
            assertThat(source.path("url").asText()).startsWith("https://");
            assertThat(source.has("publishedAt")).isTrue();
            if (source.path("publishedAt").isNull()) {
                assertThat(source.path("note").asText())
                        .as("source %s explains why its publication date is unavailable", id)
                        .isNotBlank();
            } else {
                assertThat(LocalDate.parse(source.path("publishedAt").asText()))
                        .as("source %s must be published by verification date", id)
                        .isBeforeOrEqualTo(verifiedAt);
            }
            assertThat(LocalDate.parse(source.path("accessedAt").asText()))
                    .as("source %s must be accessed by verification date", id)
                    .isBeforeOrEqualTo(verifiedAt);
        }

        Map<String, JsonNode> facts = new HashMap<>();
        assertThat(context.path("facts").size()).isPositive();
        for (JsonNode fact : context.path("facts")) {
            String id = fact.path("id").asText();
            assertThat(id).isNotBlank();
            assertThat(facts.put(id, fact)).as("unique fact id: %s", id).isNull();
            assertThat(fact.path("sourceIds").isArray()).isTrue();
            assertThat(fact.path("sourceIds").size()).as("sources for fact %s", id).isPositive();
            assertThat(LocalDate.parse(fact.path("asOf").asText()))
                    .as("fact %s must describe evidence available by verification date", id)
                    .isBeforeOrEqualTo(verifiedAt);
        }
        assertEvidenceReferencesResolve(context, sourceIds, verifiedAt);
        assertThat(facts).containsKeys("route_length", "station_count");
        assertThat(facts.get("route_length").path("value").asDouble()).isEqualTo(22.4);
        assertThat(facts.get("station_count").path("value").asInt()).isEqualTo(18);
    }

    private void assertEvidenceReferencesResolve(JsonNode node, Set<String> sourceIds, LocalDate verifiedAt) {
        if (node.has("sourceIds")) {
            assertThat(node.path("sourceIds").isArray()).isTrue();
            assertThat(node.path("sourceIds").size()).isPositive();
            for (JsonNode sourceId : node.path("sourceIds")) {
                assertThat(sourceIds).as("referenced source %s", sourceId.asText()).contains(sourceId.asText());
            }
        }
        if (node.has("asOf")) {
            assertThat(LocalDate.parse(node.path("asOf").asText()))
                    .as("evidence must be available by verification date")
                    .isBeforeOrEqualTo(verifiedAt);
        }
        for (JsonNode child : node) {
            assertEvidenceReferencesResolve(child, sourceIds, verifiedAt);
        }
    }

    @Test
    void lrtMapPreservesOnlyOriginalLightRailGeometry() throws Exception {
        JsonNode original;
        try (var input = new ClassPathResource("map-data/astana/roads_main.geojson").getInputStream()) {
            original = json.readTree(input);
        }
        Map<String, JsonNode> originalRailFeatures = new HashMap<>();
        for (JsonNode feature : original.path("features")) {
            if ("light_rail".equals(feature.path("properties").path("class").asText())) {
                originalRailFeatures.put(feature.path("properties").path("id").asText(), feature);
            }
        }
        assertThat(originalRailFeatures).isNotEmpty();

        var response = mockMvc.perform(get(MAP_URL))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(GEO_JSON))
                .andExpect(jsonPath("$.type").value("FeatureCollection"))
                .andReturn();
        JsonNode collection = json.readTree(response.getResponse().getContentAsString());
        int featureCount = collection.path("features").size();
        assertThat(featureCount).isEqualTo(originalRailFeatures.size());
        assertThat(collection.path("metadata").path("feature_count").asInt()).isEqualTo(featureCount);
        Set<String> returnedIds = new HashSet<>();
        for (JsonNode feature : collection.path("features")) {
            JsonNode properties = feature.path("properties");
            String id = properties.path("id").asText();
            assertThat(returnedIds.add(id)).as("unique rail feature id: %s", id).isTrue();
            assertThat(originalRailFeatures).containsKey(id);
            assertThat(properties.path("class").asText()).isEqualTo("light_rail");
            assertThat(properties.path("measure_id").asText()).isEqualTo("M3");
            assertThat(feature.path("type").asText()).isEqualTo("Feature");
            assertThat(feature.path("geometry").path("type").asText())
                    .as("the partial map must not invent station points")
                    .isIn("LineString", "MultiLineString");
            assertThat(feature.path("geometry")).isEqualTo(originalRailFeatures.get(id).path("geometry"));
        }
        assertThat(returnedIds).containsExactlyInAnyOrderElementsOf(originalRailFeatures.keySet());

        var manifestResponse = mockMvc.perform(get("/api/v1/map/layers"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode manifest = json.readTree(manifestResponse.getResponse().getContentAsString());
        int lrtLayers = 0;
        for (JsonNode layer : manifest.path("layers")) {
            if ("lrt".equals(layer.path("id").asText())) {
                lrtLayers++;
                assertThat(layer.path("url").asText()).isEqualTo(MAP_URL);
                assertThat(layer.path("count").asInt()).isEqualTo(featureCount);
            }
        }
        assertThat(lrtLayers).isEqualTo(1);
    }

    @Test
    void measureCatalogsLinkOnlyM3ToLrtContextAndMap() throws Exception {
        for (String url : new String[]{"/api/v1/measures", "/api/v1/districts/nura/measures"}) {
            var response = mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn();
            JsonNode measures = json.readTree(response.getResponse().getContentAsString());
            int lrtMeasures = 0;
            for (JsonNode measure : measures) {
                if ("M3".equals(measure.path("id").asText())) {
                    lrtMeasures++;
                    assertThat(measure.path("contextUrl").asText()).isEqualTo(CONTEXT_URL);
                    assertThat(measure.path("mapLayerUrl").asText()).isEqualTo(MAP_URL);
                    assertThat(measure.path("cost").asInt()).isEqualTo(30);
                    assertThat(measure.path("lagQuarters").asInt()).isEqualTo(4);
                    assertThat(measure.path("realizationFactor").asDouble()).isEqualTo(0.5);
                    assertThat(measure.path("fullEffects").size()).isEqualTo(3);
                    assertThat(measure.path("fullEffects").path("T1").asInt()).isEqualTo(16);
                    assertThat(measure.path("fullEffects").path("T2").asInt()).isEqualTo(20);
                    assertThat(measure.path("fullEffects").path("E2").asInt()).isEqualTo(4);
                    assertThat(measure.path("realizedEffects").size()).isEqualTo(3);
                    assertThat(measure.path("realizedEffects").path("T1").asDouble()).isEqualTo(8.0);
                    assertThat(measure.path("realizedEffects").path("T2").asDouble()).isEqualTo(10.0);
                    assertThat(measure.path("realizedEffects").path("E2").asDouble()).isEqualTo(2.0);
                } else {
                    assertThat(measure.path("contextUrl").isNull()).isTrue();
                    assertThat(measure.path("mapLayerUrl").isNull()).isTrue();
                }
            }
            assertThat(lrtMeasures).as("M3 in catalog %s", url).isEqualTo(1);
        }
    }

    @Test
    void openApiDescribesLrtResponsesAsJsonObjects() throws Exception {
        var response = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();
        JsonNode document = json.readTree(response.getResponse().getContentAsString());
        Map<String, String> mediaTypes = Map.of(
                CONTEXT_URL, "application/json",
                MAP_URL, "application/geo+json");
        for (var endpoint : mediaTypes.entrySet()) {
            JsonNode responseContent = document.path("paths").path(endpoint.getKey())
                    .path("get").path("responses").path("200").path("content");
            assertThat(responseContent.size()).as("response media types for %s", endpoint.getKey()).isEqualTo(1);
            assertThat(responseContent.has(endpoint.getValue())).isTrue();
            JsonNode schema = responseContent.path(endpoint.getValue()).path("schema");
            assertThat(schema.path("type").asText()).as("schema type for %s", endpoint.getKey()).isEqualTo("object");
            assertThat(schema.path("format").asText()).isNotEqualTo("byte");
            assertThat(schema.toString()).doesNotContain("JsonNode");
        }
    }

    @Test
    void bootstrapExposesLrtLayer() throws Exception {
        mockMvc.perform(get("/api/v1/simulation/bootstrap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.map.lrt").value(MAP_URL));
    }

    @Test
    void unknownMeasureHasNoContext() throws Exception {
        mockMvc.perform(get("/api/v1/measures/M999/context"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
