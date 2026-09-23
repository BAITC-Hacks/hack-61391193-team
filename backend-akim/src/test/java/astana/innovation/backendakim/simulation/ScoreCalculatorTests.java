package astana.innovation.backendakim.simulation;

import astana.innovation.backendakim.catalog.CatalogService;
import astana.innovation.backendakim.catalog.DistrictResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import static astana.innovation.backendakim.simulation.SimulationRequest.Decision;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScoreCalculatorTests {
    private final CatalogService catalog = new CatalogService();
    private final ScoreCalculator calculator = new ScoreCalculator(catalog);
    private final SimulationValidator validator = new SimulationValidator(catalog);
    private static final SimulationOptimizer OPTIMIZER = optimizer();
    private final SimulationService service = new SimulationService(validator, calculator, new SimulationExplanationService(),
            OPTIMIZER, mock(SimulationLlmClient.class));

    private static SimulationOptimizer optimizer() {
        var catalog = new CatalogService();
        return new SimulationOptimizer(catalog, new SimulationValidator(catalog), new ScoreCalculator(catalog));
    }

    static SimulationRequest example() {
        return new SimulationRequest(List.of(new Decision("M7", "nura"), new Decision("M8", "nura"),
                new Decision("M10", "nura"), new Decision("M12", null), new Decision("M5", "saryarka")));
    }

    @Test
    void baselineMatchesIndependentlyCalculatedDataset() {
        var result = service.baseline();
        assertThat(result.finalScore()).isEqualByComparingTo("52.55768");
        assertThat(result.summary().dAvg()).isEqualByComparingTo("56.8624");
        assertThat(result.summary().dMin()).isEqualByComparingTo("49.18");
        assertThat(result.summary().nCrit()).isEqualTo(2);
        assertThat(result.summary().criticalMetrics()).extracting(SimulationResult.CriticalMetric::metric).containsExactly("S1", "S2");
        assertThat(result.districts()).extracting(d -> d.scoreBefore().stripTrailingZeros())
                .containsExactly(new BigDecimal("62.99"), new BigDecimal("57.06"), new BigDecimal("54.65"),
                        new BigDecimal("56.63"), new BigDecimal("49.18"));
    }

    @Test
    void documentExampleMatchesHandCalculationIncludingSynergyAndLag() {
        var result = service.calculate(example());
        assertThat(result.finalScore()).isEqualByComparingTo("56.54307");
        assertThat(result.displayScore()).isEqualByComparingTo("56.54");
        assertThat(result.scoreDelta()).isEqualByComparingTo("3.98539");
        assertThat(result.budget().spent()).isEqualTo(95);
        assertThat(result.budget().remaining()).isEqualTo(5);
        assertThat(result.summary().dAvg()).isEqualByComparingTo("58.0776");
        assertThat(result.summary().dMin()).isEqualByComparingTo("52.9625");
        assertThat(result.summary().nCrit()).isZero();
        assertThat(result.summary().weakestDistrictId()).isEqualTo("nura");
        var nura = result.districts().get(4);
        assertThat(nura.after().get("S1")).isEqualByComparingTo("48");
        assertThat(nura.after().get("S2")).isEqualByComparingTo("43.75");
        assertThat(nura.after().get("B1")).isEqualByComparingTo("67.5");
        assertThat(nura.after().get("B2")).isEqualByComparingTo("51.75");
        assertThat(result.synergies()).hasSize(1);
        assertThat(result.synergies().getFirst().bonus()).isEqualByComparingTo("2");
        // Citywide M12 gives every district +4.375, not the full +5.
        result.districts().forEach(d -> assertThat(d.metricDeltas().get("C2")).isEqualByComparingTo("4.375"));
        assertThat(result.districts().get(2).scoreAfter()).isEqualByComparingTo("56.3");
        assertThat(result.explanation().source()).isEqualTo("template");
        assertThat(result.explanation().summary()).contains("56.54", "+3.99", "95 из 100");
    }

    @Test
    void requestOrderAndRepeatedCallsCannotChangeResultsOrMutateBaseline() {
        var expected = service.calculate(example());
        var reversed = new ArrayList<>(example().decisions());
        Collections.reverse(reversed);
        assertThat(service.calculate(new SimulationRequest(reversed))).isEqualTo(expected);
        assertThat(service.calculate(example())).isEqualTo(expected);
        assertThat(service.baseline().finalScore()).isEqualByComparingTo("52.55768");
        assertThat(catalog.getDistrict("nura").metrics().get("S1")).isEqualTo(38);
    }

    @Test
    void transportSynergyIsFixedAndOnlyInM1District() {
        var result = service.calculate(new SimulationRequest(List.of(new Decision("M1", "nura"),
                new Decision("M2", null), new Decision("M4", "esil"), new Decision("M9", "nura"), new Decision("M12", null))));
        assertThat(result.districts().get(4).after().get("T1")).isEqualByComparingTo("64.5");
        assertThat(result.districts().getFirst().metricDeltas().get("T1")).isEqualByComparingTo("3");
        assertThat(result.synergies()).hasSize(1);
        assertThat(result.synergies().getFirst().districtId()).isEqualTo("nura");
    }

    @Test
    void ecologyAndSafetySynergiesCanApplyTogetherInDifferentDistricts() {
        var result = service.calculate(new SimulationRequest(List.of(new Decision("M5", "saryarka"),
                new Decision("M6", null), new Decision("M9", "nura"), new Decision("M10", "esil"), new Decision("M12", null))));
        assertThat(result.synergies()).hasSize(2);
        assertThat(result.districts().get(2).after().get("E2")).isEqualByComparingTo("52.25");
        assertThat(result.districts().get(4).metricDeltas().get("E2")).isEqualByComparingTo("1.5");
        assertThat(result.districts().getFirst().after().get("B1")).isEqualByComparingTo("90.5");
    }

    @Test
    void accountsForNegativeEffectsAndResidualCriticalMetrics() {
        var result = service.calculate(new SimulationRequest(List.of(new Decision("M11", "nura"),
                new Decision("M9", "nura"), new Decision("M4", "nura"), new Decision("M12", null), new Decision("M14", null))));
        assertThat(result.districts().get(4).after().get("T1")).isEqualByComparingTo("53.25");
        assertThat(result.districts().get(4).after().get("B2")).isEqualByComparingTo("60.5");
        assertThat(result.summary().nCrit()).isEqualTo(1);
        assertThat(result.explanation().risks()).anyMatch(r -> r.contains("T1") && r.contains("1.75"));
    }

    @Test
    void clipsOnceAfterSummingPositiveAndNegativeEffects() {
        CatalogService customCatalog = catalogWithMetrics(98, 99);
        ScoreCalculator custom = new ScoreCalculator(customCatalog);
        var m2 = new SimulationValidator.SelectedMeasure(catalog.getMeasures().get(1), null);
        var m11 = new SimulationValidator.SelectedMeasure(catalog.getMeasures().get(10), "esil");
        var result = custom.calculate(List.of(m2, m11));
        assertThat(result.districts().getFirst().after().get("T1")).isEqualByComparingTo("99.25");
        assertThat(result.districts().getFirst().after().get("B2")).isEqualByComparingTo("100");
        assertThat(custom.calculate(List.of(m11, m2)).summary()).isEqualTo(result.summary());
        var low = new ScoreCalculator(catalogWithMetrics(1, 50)).calculate(List.of(m11));
        assertThat(low.districts().getFirst().after().get("T1")).isEqualByComparingTo("0");
    }

    @Test
    void criticalThresholdIsStrictlyBelowFortyAndBaselineIsComputedFromMetrics() {
        var atThreshold = new ScoreCalculator(catalogWithMetrics(40, 40)).calculate(List.of());
        assertThat(atThreshold.summary().nCrit()).isZero();
        var below = new ScoreCalculator(catalogWithMetrics(39, 40)).calculate(List.of());
        assertThat(below.summary().nCrit()).isEqualTo(1);
        assertThat(atThreshold.baseline().dMin()).isEqualByComparingTo("48.1");
    }

    private CatalogService catalogWithMetrics(int t1, int b2) {
        CatalogService custom = mock(CatalogService.class);
        var metrics = new LinkedHashMap<String, Integer>();
        SimulationRules.METRIC_WEIGHTS.keySet().forEach(key -> metrics.put(key, 50));
        metrics.put("T1", t1);
        metrics.put("B2", b2);
        // Deliberately wrong precomputed D: the calculator must use the metrics.
        when(custom.getDistricts()).thenReturn(List.of(new DistrictResponse("esil", "Есиль", 1, metrics, 0)));
        return custom;
    }
}
