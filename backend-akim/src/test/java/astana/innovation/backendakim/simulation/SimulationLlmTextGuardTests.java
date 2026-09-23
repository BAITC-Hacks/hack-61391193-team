package astana.innovation.backendakim.simulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

import static astana.innovation.backendakim.simulation.SimulationFixtures.JSON;
import static org.assertj.core.api.Assertions.assertThat;

class SimulationLlmTextGuardTests {
    private static final SimulationLlmFacts FACTS =
            SimulationLlmFacts.of(SimulationFixtures.example(), SimulationFixtures.templateResult(SimulationFixtures.example()));
    private static final JsonNode TREE = JSON.valueToTree(FACTS.payload());

    @ParameterizedTest
    @ValueSource(strings = {
            "Score 56.32, база 52.33, рост +3.99.",
            "Score около 56,3 — примерно 56 баллов.",
            "Разница с лучшим набором 0,69: он даёт 57.01 за 98 из 100.",
            "Показатели T1, S2 и мера M12 названы по ID; 5 мер, 3 квартала.",
            "Меры М7 и М8 (кириллицей) закрывают 2 критических показателя; 70% веса — город, 30% — слабейший район.",
            "Эффект M5 реализуется на 5/8 за 8 кварталов."})
    void acceptsNumbersAndMeasuresFromTheFacts(String text) {
        assertThat(SimulationLlmTextGuard.rejection(text, TREE, FACTS.measureIds())).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Score вырос до 58.12.|number 58.12",
            "Бюджет использован на 95%, экономия 17 единиц.|number 17",
            "Перебрано 694 395 вариантов.|number 694",
            "Добавьте M13 в Алматы.|measure M13",
            "Добавьте М15 в Алматы.|measure M15"})
    void rejectsUngroundedClaims(String text, String reason) {
        assertThat(SimulationLlmTextGuard.rejection(text, TREE, FACTS.measureIds())).hasValueSatisfying(r -> assertThat(r).contains(reason));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"summary\":\"Score 56.32\"}",
            "Итог:\n```\nScore 56.32\n```",
            "Your plan scores 56.32 and the best plan scores 57.01 in this model."})
    void rejectsNonProseOrNonRussianAnswers(String text) {
        assertThat(SimulationLlmTextGuard.rejection(text, TREE, FACTS.measureIds())).isPresent();
    }

    @Test
    void rejectsOverlongAnswers() {
        String text = "Нура ".repeat(SimulationLlmTextGuard.MAX_LENGTH);
        assertThat(SimulationLlmTextGuard.rejection(text, TREE, FACTS.measureIds())).hasValueSatisfying(r -> assertThat(r).contains("longer"));
    }

    @Test
    void factsNeverExposeUnroundedNumbers() {
        assertThat(TREE.toString()).doesNotContain("56.31781049", "57.01147549", "0.693665");
        assertThat(FACTS.measureIds()).containsExactlyInAnyOrder("M5", "M7", "M8", "M10", "M12", "M2", "M3", "M9", "M14");
    }
}
