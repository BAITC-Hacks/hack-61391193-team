package astana.innovation.backendakim.history;

import astana.innovation.backendakim.catalog.CatalogService;
import astana.innovation.backendakim.catalog.DistrictRepository;
import astana.innovation.backendakim.simulation.SimulationRequest;
import astana.innovation.backendakim.simulation.SimulationService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "app.auth.jwt.secret=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="
})
@AutoConfigureMockMvc
@ActiveProfiles("postgres")
@Testcontainers(disabledWithoutDocker = true)
class SimulationHistoryIntegrationTests {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15-alpine3.23");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired CatalogService catalog;
    @Autowired DistrictRepository districts;
    @MockitoSpyBean SimulationService simulation;
    @MockitoSpyBean SimulationHistoryRepository history;

    @Test
    void postgresCatalogMatchesTheVersionedStandaloneDatasetIncludingProvenance() {
        assertThat(districts.findCurrent()).hasSize(6).isEqualTo(new CatalogService().getDistricts());
        assertThat(catalog.getDistricts()).isEqualTo(districts.findCurrent());
        assertThat(jdbc.queryForObject("SELECT sum(population_share) FROM akim.districts", BigDecimal.class))
                .isEqualByComparingTo(BigDecimal.ONE);
        assertThat(jdbc.queryForObject("SELECT data_provenance ->> 'modelVersion' FROM akim.districts WHERE id = 'saraishyk'",
                String.class)).isEqualTo("v2-saraishyk");
    }

    @Test
    void calculatesOutsideDatabaseTransactionAndInsertsAtomically() throws Exception {
        var user = createUser();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return invocation.callRealMethod();
        }).when(simulation).calculate(any());
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return invocation.callRealMethod();
        }).when(history).insert(any());

        var saved = save(user);
        assertThat(saved.path("result").path("bestSolution").path("provenOptimal").asBoolean()).isTrue();
        assertThat(saved.path("result").path("comparison").path("scoreGap").decimalValue())
                .isEqualByComparingTo("0.693665");
        assertThat(count(user)).isEqualTo(1);
    }

    @Test
    void savesFullSnapshotAndReadsItWithoutRecalculating() throws Exception {
        var user = createUser();
        var saved = save(user);
        String id = saved.path("id").asText();
        assertThat(saved.path("userId").asText()).isEqualTo(user.userId().toString());
        assertThat(saved.path("result").path("finalScore").decimalValue()).isEqualByComparingTo("56.31781049");
        assertThat(saved.path("result").path("districts")).hasSize(6);
        assertThat(saved.path("result").path("districts").path(5).path("dataProvenance").path("syntheticMetrics").asBoolean()).isTrue();
        assertThat(saved.path("request").path("decisions")).hasSize(5);

        doThrow(new AssertionError("Reading history must not recalculate scores"))
                .when(simulation).calculate(any());
        var loaded = json.readTree(mvc.perform(get("/api/v1/simulations/" + id)
                        .header("Authorization", bearer(user)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(loaded).isEqualTo(saved);
        assertThat(jdbc.queryForObject("SELECT result ->> 'modelVersion' FROM akim.simulations WHERE id = ?",
                String.class, UUID.fromString(id))).isEqualTo("v2-saraishyk");
        assertThat(count(user)).isEqualTo(1);
    }

    @Test
    void keepsHistoryPrivateAndStoresOnlyHashedTokens() throws Exception {
        var owner = createUser();
        var other = createUser();
        String id = save(owner).path("id").asText();

        mvc.perform(get("/api/v1/simulations/" + id).header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/simulations/" + UUID.randomUUID()).header("Authorization", bearer(owner)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/simulations").header("Authorization", bearer(other))
                        .param("userId", owner.userId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)));

        for (String authorization : new String[]{"", "Bearer invalid", "Bearer " + AnonymousTokens.generate()}) {
            mvc.perform(get("/api/v1/simulations").header("Authorization", authorization))
                    .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
            mvc.perform(post("/api/v1/simulations").header("Authorization", authorization)
                            .contentType(MediaType.APPLICATION_JSON).content(SimulationRequest.EXAMPLE_JSON))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/v1/simulations")).andExpect(status().isUnauthorized());
        assertThat(count(owner)).isEqualTo(1);
        String storedHash = jdbc.queryForObject("SELECT token_hash FROM akim.users WHERE id = ?",
                String.class, owner.userId());
        assertThat(storedHash).isEqualTo(AnonymousTokens.hash(owner.accessToken())).doesNotContain(owner.accessToken());
        assertThat(owner.toString()).doesNotContain(owner.accessToken());
    }

    @Test
    void readsHistoricalFiveDistrictV1SnapshotWithoutRecalculatingOrAddingNewProvenance() throws Exception {
        var user = createUser();
        UUID id = UUID.randomUUID();
        String oldResult;
        try (var input = getClass().getResourceAsStream("/history/simulation-result-v1.json")) {
            assertThat(input).isNotNull();
            oldResult = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        jdbc.update("""
                INSERT INTO akim.simulations
                    (id, user_id, created_at, model_version, request, result,
                     final_score, baseline_score, score_delta, budget_spent)
                VALUES (?, ?, CURRENT_TIMESTAMP, 'v1', CAST(? AS jsonb), CAST(? AS jsonb),
                        56.54307, 52.55768, 3.98539, 95)
                """, id, user.userId(), SimulationRequest.EXAMPLE_JSON, oldResult);
        doThrow(new AssertionError("A v1 history snapshot must not be recalculated using v2"))
                .when(simulation).calculate(any());

        var restored = json.readTree(mvc.perform(get("/api/v1/simulations/" + id).header("Authorization", bearer(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.modelVersion").value("v1"))
                .andExpect(jsonPath("$.result.finalScore").value(56.54307))
                .andExpect(jsonPath("$.result.baselineScore").value(52.55768))
                .andExpect(jsonPath("$.result.districts", hasSize(5)))
                .andExpect(jsonPath("$.result.bestSolution").doesNotExist())
                .andExpect(jsonPath("$.result.comparison").doesNotExist())
                .andReturn().getResponse().getContentAsString()).path("result");
        for (var district : restored.path("districts")) {
            assertThat(district.path("id").asText()).isNotEqualTo("saraishyk");
            assertThat(district.path("dataProvenance").isNull() || district.path("dataProvenance").isMissingNode()).isTrue();
        }
        mvc.perform(get("/api/v1/simulations").header("Authorization", bearer(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].modelVersion").value("v1"))
                .andExpect(jsonPath("$.items[0].finalScore").value(56.54307));
        String stored = jdbc.queryForObject("SELECT result::text FROM akim.simulations WHERE id = ?", String.class, id);
        assertThat(json.readTree(stored)).isEqualTo(json.readTree(oldResult));
    }

    @Test
    void persistsSaraishykSelectionAndItsModelProvenance() throws Exception {
        var user = createUser();
        var saved = json.readTree(mvc.perform(post("/api/v1/simulations").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content(SimulationRequest.SARAISHYK_EXAMPLE_JSON))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String id = saved.path("id").asText();
        assertThat(saved.path("request").path("decisions").path(0).path("districtId").asText()).isEqualTo("saraishyk");
        assertThat(saved.path("result").path("finalScore").decimalValue()).isEqualByComparingTo("54.95216719");
        assertThat(saved.path("result").path("districts").path(5).path("dataProvenance").path("metricAssumptions")).hasSize(10);
        var loaded = json.readTree(mvc.perform(get("/api/v1/simulations/" + id).header("Authorization", bearer(user)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(loaded).isEqualTo(saved);
    }

    @Test
    void rejectsInvalidScenariosWithoutSavingAnything() throws Exception {
        var user = createUser();
        for (String request : new String[]{"{}", "{\"decisions\":[]}",
                SimulationRequest.EXAMPLE_JSON.replace("\"M8\"", "\"M7\""),
                SimulationRequest.EXAMPLE_JSON.replace("\"M10\"", "\"M3\"")}) {
            mvc.perform(post("/api/v1/simulations").header("Authorization", bearer(user))
                            .contentType(MediaType.APPLICATION_JSON).content(request))
                    .andExpect(status().is(422)).andExpect(jsonPath("$.errors").isArray());
        }
        mvc.perform(post("/api/v1/simulations").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
        assertThat(count(user)).isZero();
    }

    @Test
    void paginatesHistoryFromNewestToOldest() throws Exception {
        var user = createUser();
        String first = save(user).path("id").asText();
        String second = save(user).path("id").asText();
        // Fix timestamps to make the expected ordering independent of clock resolution.
        jdbc.update("UPDATE akim.simulations SET created_at = TIMESTAMPTZ '2026-01-01 00:00:00+00' WHERE id = ?",
                UUID.fromString(first));
        jdbc.update("UPDATE akim.simulations SET created_at = TIMESTAMPTZ '2026-01-02 00:00:00+00' WHERE id = ?",
                UUID.fromString(second));
        mvc.perform(get("/api/v1/simulations?limit=1&offset=0").header("Authorization", bearer(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(second)).andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.items[0].budgetSpent").value(95));
        mvc.perform(get("/api/v1/simulations?limit=1&offset=1").header("Authorization", bearer(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(first))
                .andExpect(jsonPath("$.hasMore").value(false));
        mvc.perform(get("/api/v1/simulations?offset=2").header("Authorization", bearer(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.hasMore").value(false));
        for (String query : new String[]{"limit=0", "limit=101", "offset=-1", "limit=abc"}) {
            mvc.perform(get("/api/v1/simulations?" + query).header("Authorization", bearer(user)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void rollsBackAnInsertedScenarioWhenTheStorageOperationFails() throws Exception {
        var user = createUser();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DataAccessResourceFailureException("Simulated failure after INSERT");
        }).when(history).insert(any());

        mvc.perform(post("/api/v1/simulations").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content(SimulationRequest.EXAMPLE_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.result").doesNotExist());
        assertThat(count(user)).isZero();
    }

    private AnonymousUserResponse createUser() throws Exception {
        return json.readValue(mvc.perform(post("/api/v1/users/anonymous"))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString(), AnonymousUserResponse.class);
    }

    private JsonNode save(AnonymousUserResponse user) throws Exception {
        var response = mvc.perform(post("/api/v1/simulations").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content(SimulationRequest.EXAMPLE_JSON))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse();
        var saved = json.readTree(response.getContentAsString());
        assertThat(response.getHeader("Location")).isEqualTo("/api/v1/simulations/" + saved.path("id").asText());
        return saved;
    }

    private long count(AnonymousUserResponse user) {
        return jdbc.queryForObject("SELECT count(*) FROM akim.simulations WHERE user_id = ?", Long.class, user.userId());
    }

    private String bearer(AnonymousUserResponse user) { return "Bearer " + user.accessToken(); }
}
