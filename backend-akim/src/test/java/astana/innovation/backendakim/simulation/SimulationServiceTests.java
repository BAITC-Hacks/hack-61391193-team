package astana.innovation.backendakim.simulation;

import astana.innovation.backendakim.catalog.CatalogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;

import static astana.innovation.backendakim.simulation.SimulationRequest.Decision;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SimulationServiceTests {
    private final CatalogService catalog = new CatalogService();
    private final SimulationValidator validator = new SimulationValidator(catalog);
    private final ScoreCalculator calculator = new ScoreCalculator(catalog);
    private final SimulationOptimizer optimizer = mock(SimulationOptimizer.class);
    private final SimulationLlmClient llm = mock(SimulationLlmClient.class);
    private final SimulationService service = new SimulationService(validator, calculator,
            new SimulationExplanationService(), optimizer, llm);

    @ParameterizedTest(name = "{0}")
    @MethodSource("astana.innovation.backendakim.simulation.SimulationApiIntegrationTests#invalidScenarios")
    void invalidRequestsNeverStartOptimizationOrCallLlm(String code, String json) {
        var request = new ObjectMapper().readValue(json, SimulationRequest.class);

        assertThatThrownBy(() -> service.calculate(request)).isInstanceOf(SimulationValidationException.class);

        verifyNoInteractions(optimizer, llm);
    }

    @Test
    void nullRequestNeverStartsOptimizationOrCallsLlm() {
        assertThatThrownBy(() -> service.calculate(null)).isInstanceOf(SimulationValidationException.class);

        verifyNoInteractions(optimizer, llm);
    }

    @Test
    void baselineDoesNotStartOptimizationOrCallLlm() {
        var result = service.baseline();

        assertThat(result.finalScore()).isEqualByComparingTo("52.55768");
        assertThat(result.explanation().source()).isEqualTo("template");
        assertThat(result.bestSolution()).isNull();
        assertThat(result.comparison()).isNull();
        verifyNoInteractions(optimizer, llm);
    }

    @Test
    void finalSubmissionSendsNormalizedDecisionsAndBothCalculatedScoresToLlmExactlyOnce() {
        var bestRequest = bestRequest();
        var bestCalculation = calculator.calculate(validator.validate(bestRequest));
        when(optimizer.optimal()).thenReturn(new SimulationOptimizer.Optimization(bestRequest, bestCalculation, 123L));
        var generated = new SimulationResult.Explanation("llm", "Explanation from the language model",
                List.of("strength"), List.of("risk"), List.of("recommendation"));
        when(llm.explain(any(), any())).thenReturn(Optional.of(generated));

        var result = service.calculate(ScoreCalculatorTests.example());

        var sentRequest = ArgumentCaptor.forClass(SimulationRequest.class);
        var sentResult = ArgumentCaptor.forClass(SimulationResult.class);
        verify(llm).explain(sentRequest.capture(), sentResult.capture());
        verify(optimizer).optimal();
        verifyNoMoreInteractions(optimizer, llm);
        assertThat(sentRequest.getValue().decisions()).containsExactly(new Decision("M5", "saryarka"),
                new Decision("M7", "nura"), new Decision("M8", "nura"), new Decision("M10", "nura"),
                new Decision("M12", null));

        var deterministic = sentResult.getValue();
        assertThat(deterministic.finalScore()).isEqualByComparingTo("56.54307");
        assertThat(deterministic.bestSolution().decisions()).isEqualTo(bestRequest.decisions());
        assertThat(deterministic.bestSolution().finalScore()).isEqualByComparingTo(bestCalculation.summary().finalScore());
        assertThat(deterministic.bestSolution().evaluatedCandidates()).isEqualTo(123L);
        assertThat(deterministic.bestSolution().provenOptimal()).isTrue();
        assertThat(deterministic.bestSolution().budget().spent()).isEqualTo(bestCalculation.spent());
        assertThat(deterministic.comparison().scoreGap())
                .isEqualByComparingTo(bestCalculation.summary().finalScore().subtract(deterministic.finalScore()));
        assertThat(deterministic.comparison().isOptimal()).isFalse();
        assertThat(deterministic.explanation().source()).isEqualTo("template");
        assertThat(result.explanation()).isEqualTo(generated);
        assertThat(result).usingRecursiveComparison().ignoringFields("explanation").isEqualTo(deterministic);
    }

    @Test
    void unavailableLlmKeepsTemplateExplanationWithBestPlanAndExactComparison() {
        var bestRequest = bestRequest();
        var bestCalculation = calculator.calculate(validator.validate(bestRequest));
        when(optimizer.optimal()).thenReturn(new SimulationOptimizer.Optimization(bestRequest, bestCalculation, 123L));
        when(llm.explain(any(), any())).thenReturn(Optional.empty());

        var result = service.calculate(ScoreCalculatorTests.example());

        var sentResult = ArgumentCaptor.forClass(SimulationResult.class);
        verify(llm).explain(any(), sentResult.capture());
        assertThat(result).isEqualTo(sentResult.getValue());
        assertThat(result.explanation().source()).isEqualTo("template");
        assertThat(result.explanation().summary())
                .contains(bestCalculation.summary().finalScore().stripTrailingZeros().toPlainString(),
                        result.comparison().scoreGap().stripTrailingZeros().toPlainString());
        assertThat(result.explanation().recommendations().getFirst()).contains("M2", "M7", "M8", "M10", "M14");
    }

    @Test
    void submittingOptimizerBestPlanProducesZeroGapAndOptimalExplanation() {
        var bestRequest = bestRequest();
        var bestCalculation = calculator.calculate(validator.validate(bestRequest));
        when(optimizer.optimal()).thenReturn(new SimulationOptimizer.Optimization(bestRequest, bestCalculation, 123L));
        when(llm.explain(any(), any())).thenReturn(Optional.empty());

        var result = service.calculate(bestRequest);

        assertThat(result.finalScore()).isEqualByComparingTo(result.bestSolution().finalScore());
        assertThat(result.comparison().scoreGap()).isZero();
        assertThat(result.comparison().isOptimal()).isTrue();
        assertThat(result.explanation().summary()).contains("Ваш набор достигает максимального Score");
    }

    @Test
    void oldSavedResultsWithoutOptimizationFieldsStillDeserialize() {
        var json = new ObjectMapper();
        var baseline = service.baseline();
        var oldResult = (ObjectNode) json.valueToTree(baseline);
        oldResult.remove("bestSolution");
        oldResult.remove("comparison");

        var restored = json.readValue(json.writeValueAsString(oldResult), SimulationResult.class);

        assertThat(restored.bestSolution()).isNull();
        assertThat(restored.comparison()).isNull();
        assertThat(restored).usingRecursiveComparison().isEqualTo(baseline);
    }

    private static SimulationRequest bestRequest() {
        // A valid stand-in for the optimizer's winner keeps orchestration tests independent of exhaustive search.
        return new SimulationRequest(List.of(new Decision("M2", null), new Decision("M7", "nura"),
                new Decision("M8", "nura"), new Decision("M10", "nura"), new Decision("M14", null)));
    }
}
