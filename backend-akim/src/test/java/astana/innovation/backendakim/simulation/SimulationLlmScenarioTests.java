package astana.innovation.backendakim.simulation;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static astana.innovation.backendakim.simulation.SimulationFixtures.JSON;
import static astana.innovation.backendakim.simulation.SimulationFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plan phase 2 scenarios: the real service and LLM client against a local chat-completions endpoint.
 * Each scenario checks the facts the model receives and that only grounded prose replaces the template.
 */
class SimulationLlmScenarioTests {
    private static final String BEST = """
            {"decisions":[{"measureId":"M2"},{"measureId":"M3","districtId":"nura"},
              {"measureId":"M8","districtId":"nura"},{"measureId":"M9","districtId":"nura"},{"measureId":"M14"}]}""";
    private static final String LEAVES_NURA_CRITICAL = """
            {"decisions":[{"measureId":"M2"},{"measureId":"M6"},{"measureId":"M10","districtId":"esil"},
              {"measureId":"M12"},{"measureId":"M14"}]}""";
    private static final String WITH_M11 = """
            {"decisions":[{"measureId":"M11","districtId":"almaty"},{"measureId":"M7","districtId":"nura"},
              {"measureId":"M8","districtId":"nura"},{"measureId":"M12"},{"measureId":"M14"}]}""";
    private static final String BUS_LANES_AND_LIGHTS = """
            {"decisions":[{"measureId":"M1","districtId":"esil"},{"measureId":"M2"},
              {"measureId":"M7","districtId":"nura"},{"measureId":"M8","districtId":"nura"},{"measureId":"M12"}]}""";

    private final AtomicReference<String> reply = new AtomicReference<>();
    /** When set, the second call (the self-correction) receives this reply instead. */
    private final AtomicReference<String> correctedReply = new AtomicReference<>();
    private final AtomicReference<JsonNode> lastMessages = new AtomicReference<>();
    private final AtomicReference<JsonNode> facts = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int status = 200;
    private volatile long delayMillis;
    private HttpServer server;
    private ExecutorService executor;
    private String endpoint;

    @BeforeEach
    void startProvider() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/v1/chat/completions", this::handle);
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
    }

    @AfterEach
    void stopProvider() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void datasetExampleSendsItsNumbersAndBestPlanAndAcceptsGroundedProse() {
        reply.set("""
                Ваш набор даёт Score 56.32 (база 52.33, +3.99) при бюджете 95 из 100. Критических показателей \
                было 2, стало 0: M7 и M8 закрывают школы и поликлиники в Нуре, а синергия M10 + M12 даёт B1 +2.
                Лучший набор этой учебной модели — M2, M3, M8, M9 и M14 — даёт 57.01, разница 0,69. \
                Он усиливает транспорт Нуры через ЛРТ (M3), но эффект начнётся только через 4 квартала.""");

        var result = calculate(SimulationFixtures.example());

        JsonNode user = facts.get().path("userPlan");
        assertThat(user.path("score").decimalValue()).isEqualByComparingTo("56.32");
        assertThat(user.path("criticalMetricsBefore").asInt()).isEqualTo(2);
        assertThat(user.path("criticalMetricsAfter").asInt()).isZero();
        assertThat(user.path("synergies").path(0).path("measures").asText()).isEqualTo("M10 + M12");
        assertThat(user.path("synergies").path(0).path("district").asText()).isEqualTo("Нура");
        assertThat(facts.get().path("bestPlan").path("score").decimalValue()).isEqualByComparingTo("57.01");
        JsonNode comparison = facts.get().path("comparison");
        assertThat(comparison.path("inBothPlans").get(0).asText()).isEqualTo("M8 Центр семейного здоровья / поликлиника — Нура");
        assertThat(comparison.path("onlyInUserPlan")).hasSize(4);
        assertThat(comparison.path("onlyInBestPlan").toString()).contains("M3 Линия ЛРТ / расширение — Нура", "M2", "M9", "M14");
        assertThat(result.explanation().source()).isEqualTo("llm");
        assertThat(result.explanation().summary()).startsWith("Ваш набор даёт Score 56.32");
        assertNumbersMatchTemplate(SimulationFixtures.example(), result);
    }

    @Test
    void saraishykIsASixthDistrictInTheFacts() {
        reply.set("Школа и детсад (M7) в Сарайшыке поднимают S1 с 48 до 58, но Нура остаётся слабейшим районом.");

        var result = calculate(request(SimulationRequest.SARAISHYK_EXAMPLE_JSON));

        JsonNode user = facts.get().path("userPlan");
        assertThat(user.path("districts")).hasSize(6);
        assertThat(user.path("districts").findValuesAsString("name")).contains("Сарайшык");
        JsonNode school = java.util.stream.StreamSupport.stream(user.path("measures").spliterator(), false)
                .filter(m -> m.path("id").asText().equals("M7")).findFirst().orElseThrow();
        assertThat(school.path("district").asText()).isEqualTo("Сарайшык");
        assertThat(user.path("largestMetricGains").toString()).contains("Сарайшык", "S1 Школы и детсады");
        assertThat(facts.get().path("comparison").path("onlyInUserPlan").toString()).contains("M7 Школа + детсад (модульное строительство) — Сарайшык");
        assertThat(result.explanation().source()).isEqualTo("llm");
        assertNumbersMatchTemplate(request(SimulationRequest.SARAISHYK_EXAMPLE_JSON), result);
    }

    @Test
    void hallucinatedScoreFallsBackToTemplateWithSameNumbers() {
        reply.set("Ваш Score составит 58.12, это лучше оптимального набора.");

        var result = calculate(SimulationFixtures.example());

        assertThat(calls.get()).isEqualTo(SimulationLlmClient.MAX_ATTEMPTS);
        assertThat(result.explanation().source()).isEqualTo("template");
        assertNumbersMatchTemplate(SimulationFixtures.example(), result);
    }

    @Test
    void computedNumberIsCorrectedOnceWithTheExactReason() {
        reply.set("Нура в лучшем плане выше на 7.77 балла.");
        correctedReply.set("Нура в лучшем плане выше: 54.09 против 52.96.");

        var result = calculate(SimulationFixtures.example());

        assertThat(calls.get()).isEqualTo(2);
        JsonNode messages = lastMessages.get();
        assertThat(messages).hasSize(4);
        assertThat(messages.path(2).path("role").asText()).isEqualTo("assistant");
        assertThat(messages.path(2).path("content").asText()).contains("7.77");
        assertThat(messages.path(3).path("content").asText()).contains("number 7.77 is not in the facts");
        assertThat(result.explanation().source()).isEqualTo("llm");
        assertThat(result.explanation().summary()).isEqualTo("Нура в лучшем плане выше: 54.09 против 52.96.");
        assertThat(facts.get().path("comparison").path("weakestDistrictScoreGap").decimalValue()).isEqualByComparingTo("1.13");
    }

    @Test
    void alreadyOptimalPlanHidesBestPlanAndRejectsSuggestedAlternatives() {
        reply.set("Можно заменить M14 на M1 в районе Есиль, чтобы получить больше.");
        var rejected = calculate(request(BEST));

        assertThat(facts.get().path("comparison").path("userPlanIsOptimal").asBoolean()).isTrue();
        assertThat(facts.get().has("bestPlan")).isFalse();
        assertThat(rejected.comparison().isOptimal()).isTrue();
        assertThat(rejected.explanation().source()).isEqualTo("template");

        reply.set("Ваш набор уже оптимален в этой учебной модели: Score 57.01, бюджет 98 из 100.");
        var accepted = calculate(request(BEST));
        assertThat(accepted.explanation().source()).isEqualTo("llm");
    }

    @Test
    void remainingCriticalMetricsAreSentToTheModel() {
        reply.set("В Нуре остаются критические показатели: S1 = 38 и S2 = 35, за каждый штраф 1 балл.");

        var result = calculate(request(LEAVES_NURA_CRITICAL));

        JsonNode critical = facts.get().path("userPlan").path("remainingCriticalMetrics");
        assertThat(critical).hasSize(2);
        assertThat(critical.findValuesAsString("metric"))
                .containsExactly("S1 Школы и детсады", "S2 Поликлиники и первичная медпомощь");
        assertThat(critical.findValuesAsString("district")).containsOnly("Нура");
        assertThat(critical.path(1).path("value").decimalValue()).isEqualByComparingTo("35");
        assertThat(critical.path(1).path("before").decimalValue()).isEqualByComparingTo("35");
        assertThat(result.summary().nCrit()).isEqualTo(2);
        assertThat(result.explanation().source()).isEqualTo("llm");
    }

    @Test
    void negativeEffectOfSafeCrossingsIsReportedAsDecline() {
        reply.set("M11 в Алматы снижает разгрузку дорог T1 на 1.75: с 40 до 38.25, это новый критический показатель.");

        var result = calculate(request(WITH_M11));

        JsonNode decline = facts.get().path("userPlan").path("metricDeclines").path(0);
        assertThat(decline.path("district").asText()).isEqualTo("Алматы");
        assertThat(decline.path("metric").asText()).isEqualTo("T1 Разгрузка дорог");
        assertThat(decline.path("change").decimalValue()).isEqualByComparingTo("-1.75");
        assertThat(decline.path("after").decimalValue()).isEqualByComparingTo("38.25");
        assertThat(decline.path("causedBy").get(0).asText()).isEqualTo("M11 Безопасные переходы и школьные зоны");
        assertThat(decline.path("fellBelow40").asBoolean()).isTrue();
        assertThat(result.explanation().source()).isEqualTo("llm");
        assertThat(result.explanation().risks()).anyMatch(r -> r.contains("Алматы, T1"));
    }

    @Test
    void synergyBetweenBusLanesAndTrafficLightsIsSentToTheModel() {
        reply.set("Синергия M1 + M2 добавляет T1 +2 в районе Есиль без уменьшения на лаг.");

        var result = calculate(request(BUS_LANES_AND_LIGHTS));

        assertThat(facts.get().path("userPlan").path("synergies").findValuesAsString("measures"))
                .containsExactly("M1 + M2");
        assertThat(result.synergies()).anyMatch(s -> s.measureIds().equals(java.util.List.of("M1", "M2")));
        assertThat(result.explanation().source()).isEqualTo("llm");
    }

    @Test
    void invalidDecisionsNeverReachTheModel() {
        var overBudget = request("""
                {"decisions":[{"measureId":"M3","districtId":"nura"},{"measureId":"M13","districtId":"almaty"},
                  {"measureId":"M7","districtId":"esil"},{"measureId":"M5","districtId":"saryarka"},{"measureId":"M2"}]}""");

        try (var client = client(2000)) {
            assertThatThrownBy(() -> SimulationFixtures.service(client).calculate(overBudget))
                    .isInstanceOf(SimulationValidationException.class);
        }
        assertThat(calls.get()).isZero();
    }

    @Test
    void slowOrFailingProviderKeepsTemplateAndNumbers() {
        reply.set("Ваш набор даёт Score 56.32.");
        delayMillis = 1500;
        SimulationResult slow;
        try (var client = client(200)) {
            slow = SimulationFixtures.service(client).calculate(SimulationFixtures.example());
        }
        assertThat(slow.explanation().source()).isEqualTo("template");
        assertNumbersMatchTemplate(SimulationFixtures.example(), slow);

        delayMillis = 0;
        status = 500;
        var failing = calculate(SimulationFixtures.example());
        assertThat(failing.explanation().source()).isEqualTo("template");
        assertNumbersMatchTemplate(SimulationFixtures.example(), failing);
    }

    private SimulationResult calculate(SimulationRequest request) {
        try (var client = client(2000)) {
            return SimulationFixtures.service(client).calculate(request);
        }
    }

    private SimulationLlmClient client(long timeoutMs) {
        return new SimulationLlmClient(JSON, endpoint, "scenario-model", "scenario-key", timeoutMs);
    }

    private static void assertNumbersMatchTemplate(SimulationRequest request, SimulationResult result) {
        assertThat(result).usingRecursiveComparison().ignoringFields("explanation")
                .isEqualTo(SimulationFixtures.templateResult(request));
    }

    private void handle(HttpExchange exchange) throws IOException {
        int call = calls.incrementAndGet();
        JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
        facts.set(JSON.readTree(body.path("messages").path(1).path("content").asText()));
        lastMessages.set(body.path("messages"));
        String content = call > 1 && correctedReply.get() != null ? correctedReply.get() : reply.get();
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = JSON.writeValueAsBytes(Map.of("choices",
                java.util.List.of(Map.of("message", Map.of("role", "assistant", "content", content)))));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
