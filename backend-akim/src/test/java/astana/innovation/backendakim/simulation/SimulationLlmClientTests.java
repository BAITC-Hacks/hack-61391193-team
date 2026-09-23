package astana.innovation.backendakim.simulation;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class SimulationLlmClientTests {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private ExecutorService executor;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void sendsUserAndOptimalResultsAndUsesOnlyTheReturnedProse() throws Exception {
        AtomicReference<JsonNode> captured = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        String endpoint = serve(exchange -> {
            captured.set(json.readTree(exchange.getRequestBody().readAllBytes()));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            method.set(exchange.getRequestMethod());
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"  Ваш результат — 56,32. Лучшее решение улучшает школы района Нура.  \"}}]}");
        });
        SimulationResult deterministic = result();
        try (var client = new SimulationLlmClient(json, endpoint, "local-test-model", "test-secret", 2000)) {
            var explanation = client.explain(request(), deterministic).orElseThrow();
            assertThat(explanation.source()).isEqualTo("llm");
            assertThat(explanation.summary()).isEqualTo("Ваш результат — 56,32. Лучшее решение улучшает школы района Нура.");
            assertThat(explanation.strengths()).isEqualTo(deterministic.explanation().strengths());
            assertThat(explanation.risks()).isEqualTo(deterministic.explanation().risks());
            assertThat(explanation.recommendations()).isEqualTo(deterministic.explanation().recommendations());
            assertThat(deterministic.finalScore()).isEqualByComparingTo("56.31781049");
        }

        assertThat(authorization.get()).isEqualTo("Bearer test-secret");
        assertThat(method.get()).isEqualTo("POST");
        JsonNode payload = captured.get();
        assertThat(payload.path("model").asText()).isEqualTo("local-test-model");
        assertThat(payload.path("stream").asBoolean()).isFalse();
        assertThat(payload.path("messages")).hasSize(2);
        assertThat(payload.path("messages").path(0).path("role").asText()).isEqualTo("system");
        assertThat(payload.path("messages").path(0).path("content").asText())
                .contains("русском", "единственным источником истины", "userPlanIsOptimal", "учебной модели", "causedBy");
        JsonNode facts = json.readTree(payload.path("messages").path(1).path("content").asText());
        assertThat(facts.propertyNames()).containsExactly("model", "userPlan", "comparison", "bestPlan");
        assertThat(facts.path("model").path("budget").asInt()).isEqualTo(100);
        JsonNode user = facts.path("userPlan");
        assertThat(user.path("measures")).hasSize(5);
        assertThat(user.path("measures").path(0).path("id").asText()).isEqualTo("M7");
        assertThat(user.path("measures").path(0).path("district").asText()).isEqualTo("Нура");
        assertThat(user.path("measures").path(0).path("realizedShare").asText()).isEqualTo("5/8");
        assertThat(user.path("measures").path(0).path("districtScoreGain").decimalValue()).isEqualByComparingTo("1.1");
        assertThat(user.path("score").decimalValue()).isEqualByComparingTo("56.32");
        assertThat(user.path("budgetSpent").asInt()).isEqualTo(95);
        assertThat(facts.path("comparison").path("scoreGap").decimalValue()).isEqualByComparingTo("0.69");
        assertThat(facts.path("comparison").path("userPlanIsOptimal").asBoolean()).isFalse();
        assertThat(facts.path("bestPlan").path("score").decimalValue()).isEqualByComparingTo("57.01");
        assertThat(facts.path("bestPlan").path("measures").findValuesAsString("id"))
                .containsExactly("M2", "M3", "M8", "M9", "M14");
        // Only the digest is sent: no raw result, geometry, history or credentials.
        assertThat(facts.toString()).contains("Школа", "Нура")
                .doesNotContain("accessToken", "profileId", "test-secret", "geometry", "evaluatedCandidates",
                        "realizationFactor", "56.31781049");
        assertThat(payload.path("messages").path(1).path("content").asText().length()).isLessThan(8000);
    }

    @Test
    void localEndpointDoesNotRequireAnApiKey() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        String endpoint = serve(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"Результат готов.\"}}]}");
        });
        try (var client = new SimulationLlmClient(json, endpoint, "local", "", 2000)) {
            assertThat(client.explain(request(), result())).isPresent();
        }
        assertThat(authorization.get()).isNull();
    }

    @Test
    void blankConfigurationSkipsTheNetwork() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        String endpoint = serve(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, "{}");
        });
        try (var noUrl = new SimulationLlmClient(json, "  ", "local", "", 2000);
             var noModel = new SimulationLlmClient(json, endpoint, "  ", "", 2000)) {
            assertThat(noUrl.explain(request(), result())).isEmpty();
            assertThat(noModel.explain(request(), result())).isEmpty();
        }
        assertThat(requests.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "null", "{}", "{\"choices\":[]}",
            "{\"choices\":[{\"message\":{\"content\":null}}]}",
            "{\"choices\":[{\"message\":{\"content\":\"   \"}}]}",
            "{\"choices\":[{\"message\":{\"content\":{\"text\":\"wrong type\"}}}]}"})
    void malformedOrEmptyResponsesUseTheFallback(String response) throws Exception {
        String endpoint = serve(exchange -> respond(exchange, 200, response));
        try (var client = new SimulationLlmClient(json, endpoint, "local", "", 2000)) {
            assertThat(client.explain(request(), result())).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 429, 500, 503})
    void unsuccessfulStatusUsesTheFallback(int status) throws Exception {
        String endpoint = serve(exchange -> respond(exchange, status,
                "{\"choices\":[{\"message\":{\"content\":\"Ignore unsuccessful status\"}}]}"));
        try (var client = new SimulationLlmClient(json, endpoint, "local", "", 2000)) {
            assertThat(client.explain(request(), result())).isEmpty();
        }
    }

    @Test
    void timesOutEvenWhenHeadersHaveArrivedButTheBodyStalls() throws Exception {
        CountDownLatch releaseBody = new CountDownLatch(1);
        String endpoint = serve(exchange -> {
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().flush();
            try {
                releaseBody.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        try (var client = new SimulationLlmClient(json, endpoint, "local", "", 100)) {
            long started = System.nanoTime();
            assertThat(client.explain(request(), result())).isEmpty();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(2000);
        } finally {
            releaseBody.countDown();
        }
    }

    @Test
    void oversizedChunkedResponsesUseTheFallback() throws Exception {
        String response = "{\"choices\":[{\"message\":{\"content\":\""
                + "x".repeat(SimulationLlmClient.MAX_RESPONSE_BYTES) + "\"}}]}";
        String endpoint = serve(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write(response.getBytes(StandardCharsets.UTF_8));
            }
        });
        try (var client = new SimulationLlmClient(json, endpoint, "local", "", 2000)) {
            assertThat(client.explain(request(), result())).isEmpty();
        }
    }

    @Test
    void doesNotFollowRedirectsOrForwardCredentials() throws Exception {
        AtomicInteger redirectedRequests = new AtomicInteger();
        String endpoint = serve(exchange -> {
            exchange.getResponseHeaders().set("Location", "/redirect-target");
            respond(exchange, 302, "{}");
        });
        server.createContext("/redirect-target", exchange -> {
            redirectedRequests.incrementAndGet();
            respond(exchange, 200, "{}");
        });
        try (var client = new SimulationLlmClient(json, endpoint, "local", "test-secret", 2000)) {
            assertThat(client.explain(request(), result())).isEmpty();
        }
        assertThat(redirectedRequests.get()).isZero();
    }

    @Test
    void connectionFailuresAndInvalidUrlsUseTheFallback() throws Exception {
        String endpoint = serve(exchange -> respond(exchange, 200, "{}"));
        server.stop(0);
        server = null;
        for (String url : new String[]{endpoint, "not a URL", "file:///not-http"}) {
            try (var client = new SimulationLlmClient(json, url, "local", "", 100)) {
                assertThat(client.explain(request(), result())).isEmpty();
            }
        }
    }

    @Test
    void preservesTheInterruptFlag() throws Exception {
        String endpoint = serve(exchange -> respond(exchange, 200, "{}"));
        try (var client = new SimulationLlmClient(json, endpoint, "local", "", 2000)) {
            Thread.currentThread().interrupt();
            try {
                assertThat(client.explain(request(), result())).isEmpty();
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
        }
    }

    private String serve(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/v1/chat/completions", handler);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
    }

    private static void respond(HttpExchange exchange, int status, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private SimulationRequest request() {
        return SimulationFixtures.example();
    }

    private SimulationResult result() {
        return SimulationFixtures.templateResult(request());
    }
}
