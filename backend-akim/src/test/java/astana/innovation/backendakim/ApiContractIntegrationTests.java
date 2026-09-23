package astana.innovation.backendakim;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiContractIntegrationTests {

    private static final MediaType GEO_JSON = MediaType.parseMediaType("application/geo+json");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsFiveSimulationDistricts() throws Exception {
        mockMvc.perform(get("/api/v1/districts"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[*].id", contains(
                        "esil", "almaty", "saryarka", "baikonur", "nura")))
                .andExpect(jsonPath("$[4].name").value("Нура"))
                .andExpect(jsonPath("$[4].metrics", org.hamcrest.Matchers.aMapWithSize(10)))
                .andExpect(jsonPath("$[4].metrics.S1").value(38))
                .andExpect(jsonPath("$[4].baselineScore").value(closeTo(49.18, 0.001)));
    }

    @Test
    void returnsAllFourteenMeasures() throws Exception {
        mockMvc.perform(get("/api/v1/measures"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(14)))
                .andExpect(jsonPath("$[*].id", contains(
                        "M1", "M2", "M3", "M4", "M5", "M6", "M7",
                        "M8", "M9", "M10", "M11", "M12", "M13", "M14")))
                .andExpect(jsonPath("$[0].categoryId").value("transport"))
                .andExpect(jsonPath("$[0].categoryName").value("Транспорт"))
                .andExpect(jsonPath("$[0].scope").value("district"))
                .andExpect(jsonPath("$[0].cost").value(18))
                .andExpect(jsonPath("$[0].lagQuarters").value(2))
                .andExpect(jsonPath("$[0].realizationFactor").value(closeTo(0.75, 0.001)))
                .andExpect(jsonPath("$[0].realizedEffects.T1").value(closeTo(4.5, 0.001)))
                .andExpect(jsonPath("$[13].scope").value("city"));
    }

    @Test
    void returnsAllMeasuresInNuraContext() throws Exception {
        mockMvc.perform(get("/api/v1/districts/nura/measures"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(14)))
                .andExpect(jsonPath("$[*].id", contains(
                        "M1", "M2", "M3", "M4", "M5", "M6", "M7",
                        "M8", "M9", "M10", "M11", "M12", "M13", "M14")))
                .andExpect(jsonPath("$[0].targetDistrictId").value("nura"))
                .andExpect(jsonPath("$[0].affectedDistrictIds", contains("nura")))
                .andExpect(jsonPath("$[1].targetDistrictId").value(nullValue()))
                .andExpect(jsonPath("$[1].affectedDistrictIds", contains(
                        "esil", "almaty", "saryarka", "baikonur", "nura")));
    }

    @Test
    void returnsProblemDetailsForUnknownDistrict() throws Exception {
        mockMvc.perform(get("/api/v1/districts/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Район с id 'unknown' не найден"));
    }

    @Test
    void returnsMapLayerManifest() throws Exception {
        mockMvc.perform(get("/api/v1/map/layers"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.source").value("Overture Maps"))
                .andExpect(jsonPath("$.release").value("2026-08-19.0"))
                .andExpect(jsonPath("$.attribution").value(
                        "© OpenStreetMap contributors, Overture Maps Foundation"))
                .andExpect(jsonPath("$.layers", hasSize(6)))
                .andExpect(jsonPath("$.layers[*].id", contains(
                        "districts", "city-boundary", "district-stats", "pois", "parks", "roads")))
                .andExpect(jsonPath("$.layers[*].url", contains(
                        "/api/v1/map/districts",
                        "/api/v1/map/city-boundary",
                        "/api/v1/map/district-stats",
                        "/api/v1/map/pois",
                        "/api/v1/map/parks",
                        "/api/v1/map/roads")))
                .andExpect(jsonPath("$.layers[*].count", contains(5, 1, 5, 5317, 149, 3783)));
    }

    @Test
    void returnsDistrictBoundariesAsGeoJson() throws Exception {
        mockMvc.perform(get("/api/v1/map/districts"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(GEO_JSON))
                .andExpect(jsonPath("$.type").value("FeatureCollection"))
                .andExpect(jsonPath("$.features", hasSize(5)))
                .andExpect(jsonPath("$.features[*].properties.district_id", containsInAnyOrder(
                        "esil", "almaty", "saryarka", "baikonur", "nura")));
    }

    @Test
    void returnsOvertureDistrictStatistics() throws Exception {
        mockMvc.perform(get("/api/v1/map/district-stats"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[*].district_id", contains(
                        "esil", "almaty", "saryarka", "baikonur", "nura")))
                .andExpect(jsonPath("$[4].name_ru").value("Нура"))
                .andExpect(jsonPath("$[4].school_count").value(50))
                .andExpect(jsonPath("$[4].road_km_main").value(closeTo(189.4, 0.001)));
    }

    @Test
    void bootstrapProvidesSimulationRulesAndApiLinks() throws Exception {
        mockMvc.perform(get("/api/v1/simulation/bootstrap"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.budgetLimit").value(100))
                .andExpect(jsonPath("$.requiredDecisionCount").value(5))
                .andExpect(jsonPath("$.horizonQuarters").value(8))
                .andExpect(jsonPath("$.maxMeasuresPerCategory").value(2))
                .andExpect(jsonPath("$.baselineScore").value(closeTo(52.56, 0.001)))
                .andExpect(jsonPath("$.api.districts").value("/api/v1/districts"))
                .andExpect(jsonPath("$.api.measures").value("/api/v1/measures"))
                .andExpect(jsonPath("$.map.districts").value("/api/v1/map/districts"));
    }
}
