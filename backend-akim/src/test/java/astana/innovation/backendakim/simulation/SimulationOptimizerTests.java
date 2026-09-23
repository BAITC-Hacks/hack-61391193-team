package astana.innovation.backendakim.simulation;

import astana.innovation.backendakim.catalog.CatalogService;
import astana.innovation.backendakim.catalog.DistrictResponse;
import astana.innovation.backendakim.catalog.MeasureResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static astana.innovation.backendakim.simulation.SimulationRequest.Decision;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SimulationOptimizerTests {
    @Test
    void findsIndependentlyCalculatedGlobalOptimumAndCachesTheImmutableResult() {
        CatalogService catalog = new CatalogService();
        SimulationValidator validator = new SimulationValidator(catalog);
        ScoreCalculator calculator = new ScoreCalculator(catalog);
        SimulationOptimizer optimizer = new SimulationOptimizer(catalog, validator, calculator);

        var result = optimizer.optimal();

        // Independently enumerated with integer arithmetic at a score scale of 8,000,000,000.
        assertThat(result.evaluatedCandidates()).isEqualTo(1_580_316);
        assertThat(result.calculation().summary().finalScore()).isEqualByComparingTo("57.01147549");
        assertThat(result.calculation().spent()).isEqualTo(98);
        assertThat(result.request().decisions()).containsExactly(new Decision("M2", null), new Decision("M3", "nura"),
                new Decision("M8", "nura"), new Decision("M9", "nura"), new Decision("M14", null));
        assertThat(result.calculation()).isEqualTo(calculator.calculate(validator.validate(result.request())));
        assertThat(optimizer.optimal()).isSameAs(result);
        assertThat(catalog.getDistrict("nura").metrics().get("S1")).isEqualTo(38);
    }

    @Test
    void agreesWithFullCalculatorEnumerationForClippingNegativeEffectsSynergiesAndConflicts() {
        CatalogService source = new CatalogService();
        List<DistrictResponse> districts = List.of(
                district("zeta", .43, Map.of("T1", 99, "B2", 99, "S1", 39, "E2", 39)),
                district("alpha", .57, Map.of("T1", 1, "B2", 99, "S1", 40, "S2", 39, "E2", 100)));
        for (List<String> ids : List.of(
                List.of("M1", "M2", "M3", "M4", "M7", "M10", "M11", "M12", "M14"),
                List.of("M4", "M5", "M6", "M7", "M9", "M10", "M12", "M13", "M14"))) {
            CatalogService catalog = catalogue(source.getMeasures().stream().filter(m -> ids.contains(m.id())).toList(), districts);
            assertMatchesOracle(catalog);
        }
    }

    @Test
    void agreesWithIndependentTargetEnumerationAcrossAllSixDistricts() {
        CatalogService source = new CatalogService();
        List<String> measureIds = List.of("M1", "M2", "M3", "M8", "M9", "M14");
        CatalogService subset = catalogue(source.getMeasures().stream().filter(m -> measureIds.contains(m.id())).toList(),
                source.getDistricts());
        assertThat(subset.getDistricts()).hasSize(6).extracting(DistrictResponse::id).contains("saraishyk");
        assertMatchesOracle(subset);
    }

    @Test
    void breaksExactTiesByCostThenNumericMeasureAndDistrictIdsRegardlessOfCatalogueOrder() {
        List<MeasureResponse> measures = new ArrayList<>();
        // Different costs ensure the cost tie-break wins over the lexically earlier M2.
        for (String id : List.of("M2", "M3", "M4", "M8", "M11", "M14")) {
            measures.add(new MeasureResponse(id, id, id, id, "district", "M2".equals(id) ? 20 : 10,
                    0, 1, Map.of(), Map.of()));
        }
        Collections.reverse(measures);
        var districts = List.of(district("zeta", .5, Map.of()), district("alpha", .5, Map.of()));
        CatalogService catalog = catalogue(measures, districts);
        var result = new SimulationOptimizer(catalog, new SimulationValidator(catalog), new ScoreCalculator(catalog)).optimal();
        assertThat(result.calculation().spent()).isEqualTo(50);
        assertThat(result.request().decisions()).containsExactly(new Decision("M3", "alpha"),
                new Decision("M4", "alpha"), new Decision("M8", "alpha"), new Decision("M11", "alpha"),
                new Decision("M14", "alpha"));

        List<MeasureResponse> equalCost = measures.stream().map(m -> new MeasureResponse(m.id(), m.categoryId(),
                m.categoryName(), m.name(), m.scope(), 10, 0, 1, Map.of(), Map.of())).toList();
        CatalogService tied = catalogue(equalCost, districts);
        var tiedResult = new SimulationOptimizer(tied, new SimulationValidator(tied), new ScoreCalculator(tied)).optimal();
        assertThat(tiedResult.request().decisions()).containsExactly(new Decision("M2", "alpha"),
                new Decision("M3", "alpha"), new Decision("M4", "alpha"), new Decision("M8", "alpha"),
                new Decision("M11", "alpha"));
    }

    private static void assertMatchesOracle(CatalogService catalog) {
        SimulationValidator validator = new SimulationValidator(catalog);
        ScoreCalculator calculator = new ScoreCalculator(catalog);
        var optimized = new SimulationOptimizer(catalog, validator, calculator).optimal();
        Oracle oracle = new Oracle(catalog, validator, calculator);
        oracle.enumerate(0, new ArrayList<>());
        assertThat(optimized.evaluatedCandidates()).isEqualTo(oracle.evaluated);
        assertThat(optimized.calculation().summary().finalScore()).isEqualByComparingTo(oracle.bestScore);
        assertThat(optimized.calculation().spent()).isEqualTo(oracle.bestCost);
        assertThat(optimized.request()).isEqualTo(oracle.bestRequest);
    }

    private static DistrictResponse district(String id, double population, Map<String, Integer> overrides) {
        Map<String, Integer> metrics = new LinkedHashMap<>();
        SimulationRules.METRIC_WEIGHTS.keySet().forEach(key -> metrics.put(key, 50));
        metrics.putAll(overrides);
        return new DistrictResponse(id, id, population, metrics, 0);
    }

    private static CatalogService catalogue(List<MeasureResponse> measures, List<DistrictResponse> districts) {
        CatalogService catalog = mock(CatalogService.class);
        when(catalog.getMeasures()).thenReturn(measures);
        when(catalog.getDistricts()).thenReturn(districts);
        return catalog;
    }

    /** Deliberately unoptimized oracle: send every five-decision candidate through production arithmetic. */
    private static final class Oracle {
        private final CatalogService catalog;
        private final SimulationValidator validator;
        private final ScoreCalculator calculator;
        private final List<MeasureResponse> measures;
        private BigDecimal bestScore;
        private int bestCost;
        private SimulationRequest bestRequest;
        private long evaluated;

        Oracle(CatalogService catalog, SimulationValidator validator, ScoreCalculator calculator) {
            this.catalog = catalog;
            this.validator = validator;
            this.calculator = calculator;
            measures = catalog.getMeasures().stream()
                    .sorted(Comparator.comparingInt(m -> Integer.parseInt(m.id().substring(1)))).toList();
        }

        void enumerate(int start, List<Decision> chosen) {
            if (chosen.size() == 5) {
                SimulationRequest request = new SimulationRequest(List.copyOf(chosen));
                ScoreCalculator.Calculation result;
                try {
                    result = calculator.calculate(validator.validate(request));
                } catch (SimulationValidationException ignored) {
                    return;
                }
                evaluated++;
                BigDecimal score = result.summary().finalScore();
                int comparison = bestScore == null ? 1 : score.compareTo(bestScore);
                if (comparison > 0 || comparison == 0 && (result.spent() < bestCost
                        || result.spent() == bestCost && compare(request, bestRequest) < 0)) {
                    bestScore = score;
                    bestCost = result.spent();
                    bestRequest = request;
                }
                return;
            }
            for (int index = start; index < measures.size(); index++) {
                MeasureResponse measure = measures.get(index);
                if ("city".equals(measure.scope())) {
                    chosen.add(new Decision(measure.id(), null));
                    enumerate(index + 1, chosen);
                    chosen.removeLast();
                } else {
                    for (DistrictResponse district : catalog.getDistricts()) {
                        chosen.add(new Decision(measure.id(), district.id()));
                        enumerate(index + 1, chosen);
                        chosen.removeLast();
                    }
                }
            }
        }

        private static int compare(SimulationRequest first, SimulationRequest second) {
            for (int i = 0; i < first.decisions().size(); i++) {
                Decision a = first.decisions().get(i);
                Decision b = second.decisions().get(i);
                int value = Integer.compare(Integer.parseInt(a.measureId().substring(1)), Integer.parseInt(b.measureId().substring(1)));
                if (value != 0) return value;
                value = Comparator.nullsFirst(String::compareTo).compare(a.districtId(), b.districtId());
                if (value != 0) return value;
            }
            return 0;
        }
    }
}
