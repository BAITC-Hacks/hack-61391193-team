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
    void returnsSixSimulationDistricts() throws Exception {
        mockMvc.perform(get("/api/v1/districts"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(6)))
                .andExpect(jsonPath("$[*].id", contains(
                        "esil", "almaty", "saryarka", "baikonur", "nura", "saraishyk")))
                .andExpect(jsonPath("$[4].name").value("Нура"))
                .andExpect(jsonPath("$[4].metrics", org.hamcrest.Matchers.aMapWithSize(10)))
                .andExpect(jsonPath("$[4].metrics.S1").value(38))
                .andExpect(jsonPath("$[4].baselineScore").value(closeTo(49.18, 0.001)))
                .andExpect(jsonPath("$[5].name").value("Сарайшык"))
                .andExpect(jsonPath("$[5].populationShare").value(closeTo(0.113710, 0.0000001)))
                .andExpect(jsonPath("$[5].metrics", org.hamcrest.Matchers.aMapWithSize(10)))
                .andExpect(jsonPath("$[5].baselineScore").value(closeTo(54.23, 0.001)))
                .andExpect(jsonPath("$[5].dataProvenance.syntheticMetrics").value(true))
                .andExpect(jsonPath("$[5].dataProvenance.modelVersion").value("v2-saraishyk"));
    }

    @Test
    void returnsAllSixteenMeasures() throws Exception {
        mockMvc.perform(get("/api/v1/measures"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(16)))
                .andExpect(jsonPath("$[*].id", contains(
                        "M1", "M2", "M3", "M4", "M5", "M6", "M7",
                        "M8", "M9", "M10", "M11", "M12", "M13", "M14", "M15", "M16")))
                .andExpect(jsonPath("$[0].categoryId").value("transport"))
                .andExpect(jsonPath("$[0].categoryName").value("Транспорт"))
                .andExpect(jsonPath("$[0].scope").value("district"))
                .andExpect(jsonPath("$[0].cost").value(18))
                .andExpect(jsonPath("$[0].lagQuarters").value(2))
                .andExpect(jsonPath("$[0].realizationFactor").value(closeTo(0.75, 0.001)))
                .andExpect(jsonPath("$[0].realizedEffects.T1").value(closeTo(4.5, 0.001)))
                .andExpect(jsonPath("$[13].scope").value("city"))
                .andExpect(jsonPath("$[14].name").value("Приоритетная уборка снега и наледи"))
                .andExpect(jsonPath("$[14].categoryId").value("services"))
                .andExpect(jsonPath("$[14].categoryName").value("Сервисы"))
                .andExpect(jsonPath("$[14].scope").value("district"))
                .andExpect(jsonPath("$[14].cost").value(12))
                .andExpect(jsonPath("$[14].lagQuarters").value(1))
                .andExpect(jsonPath("$[14].realizationFactor").value(0.875))
                .andExpect(jsonPath("$[14].fullEffects.T1").value(3))
                .andExpect(jsonPath("$[14].fullEffects.T2").value(3))
                .andExpect(jsonPath("$[14].fullEffects.B2").value(8))
                .andExpect(jsonPath("$[14].realizedEffects.T1").value(2.625))
                .andExpect(jsonPath("$[14].realizedEffects.T2").value(2.625))
                .andExpect(jsonPath("$[14].realizedEffects.B2").value(7.0))
                .andExpect(jsonPath("$[15].name").value("Строительство и очистка ливневой канализации"))
                .andExpect(jsonPath("$[15].categoryId").value("services"))
                .andExpect(jsonPath("$[15].categoryName").value("Сервисы"))
                .andExpect(jsonPath("$[15].scope").value("district"))
                .andExpect(jsonPath("$[15].cost").value(22))
                .andExpect(jsonPath("$[15].lagQuarters").value(3))
                .andExpect(jsonPath("$[15].realizationFactor").value(0.625))
                .andExpect(jsonPath("$[15].fullEffects.C1").value(12))
                .andExpect(jsonPath("$[15].fullEffects.T1").value(3))
                .andExpect(jsonPath("$[15].fullEffects.B2").value(4))
                .andExpect(jsonPath("$[15].realizedEffects.C1").value(7.5))
                .andExpect(jsonPath("$[15].realizedEffects.T1").value(1.875))
                .andExpect(jsonPath("$[15].realizedEffects.B2").value(2.5));
    }

    @Test
    void returnsAllMeasuresInNuraContext() throws Exception {
        mockMvc.perform(get("/api/v1/districts/nura/measures"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(16)))
                .andExpect(jsonPath("$[*].id", contains(
                        "M1", "M2", "M3", "M4", "M5", "M6", "M7",
                        "M8", "M9", "M10", "M11", "M12", "M13", "M14", "M15", "M16")))
                .andExpect(jsonPath("$[0].targetDistrictId").value("nura"))
                .andExpect(jsonPath("$[0].affectedDistrictIds", contains("nura")))
                .andExpect(jsonPath("$[1].targetDistrictId").value(nullValue()))
                .andExpect(jsonPath("$[1].affectedDistrictIds", contains(
                        "esil", "almaty", "saryarka", "baikonur", "nura", "saraishyk")))
                .andExpect(jsonPath("$[14].targetDistrictId").value("nura"))
                .andExpect(jsonPath("$[14].affectedDistrictIds", contains("nura")))
                .andExpect(jsonPath("$[15].targetDistrictId").value("nura"))
                .andExpect(jsonPath("$[15].affectedDistrictIds", contains("nura")));
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
                .andExpect(jsonPath("$.layers", hasSize(7)))
                .andExpect(jsonPath("$.layers[*].id", contains(
                        "districts", "city-boundary", "district-stats", "pois", "parks", "roads", "lrt")))
                .andExpect(jsonPath("$.layers[*].url", contains(
                        "/api/v1/map/districts",
                        "/api/v1/map/city-boundary",
                        "/api/v1/map/district-stats",
                        "/api/v1/map/pois",
                        "/api/v1/map/parks",
                        "/api/v1/map/roads",
                        "/api/v1/map/lrt")))
                .andExpect(jsonPath("$.layers[*].count", contains(6, 1, 6, 5888, 181, 4591, 31)));
    }

    @Test
    void returnsDistrictBoundariesAsGeoJson() throws Exception {
        mockMvc.perform(get("/api/v1/map/districts"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(GEO_JSON))
                .andExpect(jsonPath("$.type").value("FeatureCollection"))
                .andExpect(jsonPath("$.features", hasSize(6)))
                .andExpect(jsonPath("$.features[*].properties.district_id", containsInAnyOrder(
                        "esil", "almaty", "saryarka", "baikonur", "nura", "saraishyk")));
    }

    @Test
    void returnsOvertureDistrictStatistics() throws Exception {
        mockMvc.perform(get("/api/v1/map/district-stats"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(6)))
                .andExpect(jsonPath("$[*].district_id", contains(
                        "esil", "almaty", "saryarka", "baikonur", "nura", "saraishyk")))
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
                .andExpect(jsonPath("$.baselineScore").value(closeTo(52.33, 0.001)))
                .andExpect(jsonPath("$.modelVersion").value("v2-saraishyk"))
                .andExpect(jsonPath("$.api.districts").value("/api/v1/districts"))
                .andExpect(jsonPath("$.api.measures").value("/api/v1/measures"))
                .andExpect(jsonPath("$.map.districts").value("/api/v1/map/districts"));
    }
}
