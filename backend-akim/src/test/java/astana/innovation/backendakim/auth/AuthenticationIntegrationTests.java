package astana.innovation.backendakim.auth;

import astana.innovation.backendakim.simulation.SimulationRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "app.auth.jwt.secret=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
        "app.auth.jwt.ttl-seconds=3600",
        "app.auth.jwt.issuer=akim-backend",
        "app.auth.jwt.audience=akim-api",
        "app.auth.admin.email=admin@example.com",
        "app.auth.admin.password=Admin",
        "app.auth.admin.username=admin"
})
@AutoConfigureMockMvc
@ActiveProfiles("postgres")
@Testcontainers(disabledWithoutDocker = true)
class AuthenticationIntegrationTests {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15-alpine3.23");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtEncoder encoder;
    @Autowired JwtDecoder decoder;
    @MockitoSpyBean AccountRepository accounts;

    @Test
    void registersAndLogsInWithNormalizedEmailAndOnlyStoresAPasswordHash() throws Exception {
        String email = uniqueEmail();
        String password = " passWord123! ";
        var registered = register("  " + email.toUpperCase(java.util.Locale.ROOT) + "  ", password, "  Ayanat  ");
        String userId = registered.path("user").path("id").asText();
        assertThat(registered.path("user").path("email").asText()).isEqualTo(email);
        assertThat(registered.path("user").path("username").asText()).isEqualTo("Ayanat");
        assertThat(registered.path("user").path("role").asText()).isEqualTo("USER");
        assertThat(registered.path("user").path("createdAt").asText()).isNotBlank();
        assertThat(registered.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(registered.path("expiresIn").asLong()).isEqualTo(3600);
        assertThat(registered.path("password").isMissingNode()).isTrue();
        assertThat(registered.path("user").path("passwordHash").isMissingNode()).isTrue();

        String hash = jdbc.queryForObject("SELECT password_hash FROM akim.users WHERE id = ?",
                String.class, UUID.fromString(userId));
        assertThat(hash).startsWith("$2").doesNotContain(password);
        assertThat(passwords.matches(password, hash)).isTrue();
        assertThat(jdbc.queryForObject("SELECT token_hash FROM akim.users WHERE id = ?",
                String.class, UUID.fromString(userId))).isNull();

        var jwt = decoder.decode(registered.path("accessToken").asText());
        assertThat(jwt.getHeaders().get("alg")).isEqualTo("HS256");
        assertThat(jwt.getSubject()).isEqualTo(userId);
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("akim-backend");
        assertThat(jwt.getAudience()).containsExactly("akim-api");
        assertThat(jwt.getExpiresAt()).isEqualTo(jwt.getIssuedAt().plusSeconds(3600));
        assertThat(jwt.getClaims()).doesNotContainKeys("password", "password_hash", "passwordHash");

        var loggedIn = login(" " + email.toUpperCase(java.util.Locale.ROOT) + " ", password);
        assertThat(loggedIn.path("user")).isEqualTo(registered.path("user"));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(loggedIn)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(userId))
                .andExpect(jsonPath("$.email").value(email)).andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(cookie().doesNotExist("JSESSIONID"));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", password.trim()))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsDuplicateAndReservedEmailsWithoutChangingExistingAccounts() throws Exception {
        String email = uniqueEmail();
        var owner = register(email, "Password123", "Original");
        for (String duplicate : new String[]{email, "  " + email.toUpperCase(java.util.Locale.ROOT) + "  ",
                "admin@example.com", " ADMIN@EXAMPLE.COM "}) {
            mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("email", duplicate, "password", "OtherPassword123", "username", "Other"))))
                    .andExpect(status().isConflict());
        }
        assertThat(login(email, "Password123").path("user")).isEqualTo(owner.path("user"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM akim.users WHERE email = ?", Long.class, email)).isEqualTo(1L);
    }

    @ParameterizedTest(name = "rejects registration: {0}")
    @MethodSource("invalidRegistrations")
    void rejectsInvalidRegistrationBeforeCreatingAnAccount(String label, Map<String, Object> overrides) throws Exception {
        String email = uniqueEmail();
        var request = new LinkedHashMap<String, Object>();
        request.put("email", email);
        request.put("password", "Password123");
        request.put("username", "Ayanat");
        request.putAll(overrides);
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM akim.users WHERE email = ?", Long.class, email)).isZero();
    }

    static Stream<Arguments> invalidRegistrations() {
        return Stream.of(
                Arguments.of("email missing", java.util.Collections.singletonMap("email", null)),
                Arguments.of("email malformed", Map.of("email", "not-an-email")),
                Arguments.of("email blank", Map.of("email", "  ")),
                Arguments.of("password missing", java.util.Collections.singletonMap("password", null)),
                Arguments.of("password short", Map.of("password", "1234567")),
                Arguments.of("password blank", Map.of("password", "        ")),
                Arguments.of("password too many characters", Map.of("password", "x".repeat(73))),
                Arguments.of("password exceeds 72 UTF-8 bytes", Map.of("password", "я".repeat(37))),
                Arguments.of("username missing", java.util.Collections.singletonMap("username", null)),
                Arguments.of("username too short after trimming", Map.of("username", "  ab  ")),
                Arguments.of("username too long", Map.of("username", "x".repeat(51)))
        );
    }

    @Test
    void acceptsTheBcryptByteBoundaryWithoutTruncatingUnicodePasswords() throws Exception {
        String email = uniqueEmail();
        String password = "я".repeat(36);
        assertThat(password.getBytes(StandardCharsets.UTF_8)).hasSize(72);
        var registered = register(email, password, "Аянат");
        assertThat(login(email, password).path("user")).isEqualTo(registered.path("user"));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", password + "x"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void usesTheSameLoginFailureForUnknownEmailAndWrongPassword() throws Exception {
        String email = uniqueEmail();
        register(email, "Password123", "Ayanat");
        JsonNode previous = null;
        for (String candidate : new String[]{email, uniqueEmail()}) {
            var response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("email", candidate, "password", "WrongPassword123"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.accessToken").doesNotExist()).andReturn().getResponse();
            var problem = json.readTree(response.getContentAsString());
            if (previous != null) {
                assertThat(problem.path("detail")).isEqualTo(previous.path("detail"));
                assertThat(problem.path("title")).isEqualTo(previous.path("title"));
            }
            assertThat(response.getContentAsString()).doesNotContain(candidate, "WrongPassword123");
            previous = problem;
        }
    }

    @Test
    void seedsRequestedAdministratorAndEnforcesTheAdminRole() throws Exception {
        var admin = login("admin@example.com", "Admin");
        assertThat(admin.path("user").path("email").asText()).isEqualTo("admin@example.com");
        assertThat(admin.path("user").path("username").asText()).isEqualTo("admin");
        assertThat(admin.path("user").path("role").asText()).isEqualTo("ADMIN");
        mvc.perform(get("/api/v1/admin/me").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"));
        var user = register(uniqueEmail(), "Password123", "Regular user");
        mvc.perform(get("/api/v1/admin/me").header("Authorization", bearer(user)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/me")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM akim.users WHERE email = 'admin@example.com' AND role = 'ADMIN'",
                Long.class)).isEqualTo(1L);
    }

    @Test
    void cannotChooseAnAdministratorRoleDuringRegistration() throws Exception {
        var response = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", uniqueEmail(), "password", "Password123",
                                "username", "Regular user", "role", "ADMIN"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.user.role").value("USER"))
                .andReturn().getResponse();
        var user = json.readTree(response.getContentAsString());
        mvc.perform(get("/api/v1/admin/me").header("Authorization", bearer(user))).andExpect(status().isForbidden());
    }

    @Test
    void anonymousHistoryCredentialsCannotAccessRegisteredProfilesOrAdministration() throws Exception {
        var anonymous = json.readTree(mvc.perform(post("/api/v1/users/anonymous"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mvc.perform(get("/api/v1/simulations").header("Authorization", bearer(anonymous)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(anonymous))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/me").header("Authorization", bearer(anonymous))).andExpect(status().isForbidden());
    }

    @Test
    void derivesPermissionsFromTheAccountInsteadOfTrustingTheRoleClaim() throws Exception {
        var user = register(uniqueEmail(), "Password123", "Regular user");
        String elevatedClaim = signedToken(user.path("user").path("id").asText(), claims -> claims.claim("role", "ADMIN"));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + elevatedClaim))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("USER"));
        mvc.perform(get("/api/v1/admin/me").header("Authorization", "Bearer " + elevatedClaim))
                .andExpect(status().isForbidden());
    }

    @Test
    void reportsAuthenticationStorageOutagesWithoutBlamingCredentialsOrLeakingDetails() throws Exception {
        var user = register(uniqueEmail(), "Password123", "Ayanat");
        doThrow(new DataAccessResourceFailureException("Database connection failed: internal-storage-details"))
                .when(accounts).findById(any(UUID.class));

        var response = mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(user)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("internal-storage-details", "Password123",
                user.path("accessToken").asText(), user.path("user").path("email").asText());
    }

    @Test
    void rejectsUnsignedTamperedExpiredAndIncompleteTokens() throws Exception {
        var user = register(uniqueEmail(), "Password123", "Ayanat");
        String subject = user.path("user").path("id").asText();
        String valid = user.path("accessToken").asText();
        String[] parts = valid.split("\\.");
        byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
        signature[0] ^= 1;
        String tampered = parts[0] + "." + parts[1] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        String unsigned = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
                + "." + parts[1] + ".";
        var tokens = List.of(
                "not-a-jwt", tampered, unsigned,
                signedToken(subject, claims -> claims.issuedAt(Instant.now().minusSeconds(7200)).expiresAt(Instant.now().minusSeconds(600))),
                signedToken(subject, claims -> claims.issuedAt(Instant.now().plusSeconds(600))),
                signedToken(subject, claims -> claims.issuer("different-issuer")),
                signedToken(subject, claims -> claims.audience(List.of("different-api"))),
                signedToken(subject, claims -> claims.claims(values -> values.remove("iss"))),
                signedToken(subject, claims -> claims.claims(values -> values.remove("aud"))),
                signedToken(subject, claims -> claims.claims(values -> values.remove("sub"))),
                signedToken(subject, claims -> claims.claims(values -> values.remove("iat"))),
                signedToken(subject, claims -> claims.claims(values -> values.remove("exp"))),
                signedToken("not-a-uuid", claims -> {}),
                signedToken(UUID.randomUUID().toString(), claims -> {})
        );
        for (String token : tokens) {
            mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(cookie().doesNotExist("JSESSIONID"));
        }
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void keepsHistoryBoundToTheRegisteredAccountAcrossLogins() throws Exception {
        String email = uniqueEmail();
        var owner = register(email, "Password123", "Owner");
        var other = register(uniqueEmail(), "Password123", "Other");
        var saved = json.readTree(mvc.perform(post("/api/v1/simulations").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(SimulationRequest.EXAMPLE_JSON))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String id = saved.path("id").asText();
        assertThat(saved.path("userId").asText()).isEqualTo(owner.path("user").path("id").asText());
        mvc.perform(get("/api/v1/simulations/" + id).header("Authorization", bearer(login(email, "Password123"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.finalScore").value(56.31781049));
        mvc.perform(get("/api/v1/simulations/" + id).header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/simulations").header("Authorization", bearer(other))
                        .param("userId", owner.path("user").path("id").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)));
        mvc.perform(get("/api/v1/simulations").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void publicLoginAndPreviewWorkWithStaleCredentialsAndWithoutServerSessions() throws Exception {
        var response = mvc.perform(post("/api/v1/auth/login").header("Authorization", "Bearer stale.token.value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@example.com\",\"password\":\"Admin\"}"))
                .andExpect(status().isOk()).andExpect(cookie().doesNotExist("JSESSIONID"))
                .andReturn().getResponse();
        assertThat(json.readTree(response.getContentAsString()).path("accessToken").asText()).isNotBlank();
        mvc.perform(post("/api/simulation/calculate").header("Authorization", "Bearer stale.token.value")
                        .contentType(MediaType.APPLICATION_JSON).content(SimulationRequest.EXAMPLE_JSON))
                .andExpect(status().isOk()).andExpect(cookie().doesNotExist("JSESSIONID"));
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mvc.perform(options("/api/v1/simulations").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    private JsonNode register(String email, String password, String username) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", password, "username", username))))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(cookie().doesNotExist("JSESSIONID"))
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode login(String email, String password) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(cookie().doesNotExist("JSESSIONID"))
                .andReturn().getResponse().getContentAsString());
    }

    private String signedToken(String subject, Consumer<JwtClaimsSet.Builder> customize) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("akim-backend").audience(List.of("akim-api"))
                .issuedAt(now).expiresAt(now.plusSeconds(3600)).subject(subject).claim("role", "USER");
        customize.accept(claims);
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
    }

    private String uniqueEmail() { return "user-" + UUID.randomUUID() + "@example.com"; }

    private String bearer(JsonNode authResponse) { return "Bearer " + authResponse.path("accessToken").asText(); }
}
