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
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"  Ваш результат — 56,54. Лучшее решение улучшает школы района Нура.  \"}}]}");
        });
        SimulationResult deterministic = result();
        try (var client = new SimulationLlmClient(json, endpoint, "local-test-model", "test-secret", 2000)) {
            var explanation = client.explain(request(), deterministic).orElseThrow();
            assertThat(explanation.source()).isEqualTo("llm");
            assertThat(explanation.summary()).isEqualTo("Ваш результат — 56,54. Лучшее решение улучшает школы района Нура.");
            assertThat(explanation.strengths()).isEqualTo(deterministic.explanation().strengths());
            assertThat(explanation.risks()).isEqualTo(deterministic.explanation().risks());
            assertThat(explanation.recommendations()).isEqualTo(deterministic.explanation().recommendations());
            assertThat(deterministic.finalScore()).isEqualByComparingTo("56.54307");
        }

        assertThat(authorization.get()).isEqualTo("Bearer test-secret");
        assertThat(method.get()).isEqualTo("POST");
        JsonNode payload = captured.get();
        assertThat(payload.path("model").asText()).isEqualTo("local-test-model");
        assertThat(payload.path("stream").asBoolean()).isFalse();
        assertThat(payload.path("messages")).hasSize(2);
        assertThat(payload.path("messages").path(0).path("role").asText()).isEqualTo("system");
        assertThat(payload.path("messages").path(0).path("content").asText())
                .contains("русском", "единственным источником истины", "глобальный оптимум");
        JsonNode data = json.readTree(payload.path("messages").path(1).path("content").asText());
        assertThat(data.path("request").path("decisions")).hasSize(5);
        assertThat(data.path("request").path("decisions").path(0).path("measureId").asText()).isEqualTo("M7");
        assertThat(data.path("result").path("finalScore").decimalValue()).isEqualByComparingTo("56.54307");
        assertThat(data.path("result").path("bestSolution").path("finalScore").decimalValue()).isEqualByComparingTo("57.12345");
        assertThat(data.path("result").path("bestSolution").path("decisions").path(0).path("districtId").asText()).isEqualTo("nura");
        assertThat(data.path("result").path("comparison").path("scoreGap").decimalValue()).isEqualByComparingTo("0.58038");
        assertThat(data.path("rules").path("formula").asText()).isEqualTo(SimulationRules.FORMULA);
        assertThat(data.path("rules").path("budget").asInt()).isEqualTo(100);
        assertThat(data.path("rules").path("maxPerCategory").asInt()).isEqualTo(2);
        assertThat(data.toString()).contains("Школа", "Нура").doesNotContain("accessToken", "profileId", "test-secret");
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
        return json.readValue(SimulationRequest.EXAMPLE_JSON, SimulationRequest.class);
    }

    private SimulationResult result() {
        return json.readValue("""
                {
                  "metricName":"Astana Quality of Life Score","modelVersion":"v1",
                  "finalScore":56.54307,"displayScore":56.54,"baselineScore":52.55768,"scoreDelta":3.98539,
                  "budget":{"limit":100,"spent":95,"remaining":5},"horizonQuarters":8,
                  "districts":[{"id":"nura","name":"Нура"}],
                  "measureEffects":[{"measureId":"M7","name":"Школа","targetDistrictId":"nura","cost":24,"lagQuarters":3}],
                  "synergies":[],
                  "explanation":{"source":"template","summary":"Детерминированный результат",
                    "strengths":["Рост показателей"],"risks":["Лаг реализации"],"recommendations":["Учесть слабейший район"]},
                  "bestSolution":{"algorithm":"exact-enumeration","provenOptimal":true,"evaluatedCandidates":100,
                    "decisions":[{"measureId":"M7","districtId":"nura"}],
                    "finalScore":57.12345,"displayScore":57.12,"scoreDelta":4.56577,
                    "budget":{"limit":100,"spent":96,"remaining":4},
                    "districts":[{"id":"nura","name":"Нура"}],
                    "measureEffects":[{"measureId":"M7","name":"Школа","targetDistrictId":"nura","cost":24,"lagQuarters":3}],"synergies":[]},
                  "comparison":{"scoreGap":0.58038,"isOptimal":false}
                }
                """, SimulationResult.class);
    }
}
