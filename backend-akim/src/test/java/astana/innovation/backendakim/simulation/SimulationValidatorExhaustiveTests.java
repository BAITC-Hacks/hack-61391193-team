package astana.innovation.backendakim.simulation;

import astana.innovation.backendakim.catalog.CatalogService;
import astana.innovation.backendakim.catalog.MeasureResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.IntStream;

import static astana.innovation.backendakim.simulation.SimulationRequest.Decision;
import static org.assertj.core.api.Assertions.assertThat;

class SimulationValidatorExhaustiveTests {
    private static final List<String> DISTRICTS = List.of("esil", "almaty", "saryarka", "baikonur", "nura");
    private static final List<MeasureSpec> SPECS = List.of(
            spec("M1", "transport", "district", 18, 2, "T1", 6, "T2", 9),
            spec("M2", "transport", "city", 22, 2, "T1", 4, "B2", 3),
            spec("M3", "transport", "district", 30, 4, "T1", 16, "T2", 20, "E2", 4),
            spec("M4", "ecology", "district", 15, 2, "E1", 12, "E2", 3, "B1", 2),
            spec("M5", "ecology", "district", 25, 3, "E2", 14, "C1", 4),
            spec("M6", "ecology", "city", 20, 4, "E1", 5, "E2", 3),
            spec("M7", "social", "district", 24, 3, "S1", 16),
            spec("M8", "social", "district", 20, 3, "S2", 14),
            spec("M9", "social", "district", 10, 1, "S1", 3, "S2", 3, "B1", 3),
            spec("M10", "safety", "district", 12, 1, "B1", 12, "B2", 2),
            spec("M11", "safety", "district", 10, 1, "B2", 12, "T1", -2),
            spec("M12", "services", "city", 14, 1, "C2", 5),
            spec("M13", "services", "district", 28, 4, "C1", 18, "E2", 2),
            spec("M14", "services", "city", 16, 1, "C1", 5, "C2", 2));
    private static final List<LocalConflict> LOCAL_CONFLICTS = List.of(
            new LocalConflict("M4", "M7", "esil", "almaty"),
            new LocalConflict("M5", "M13", "saryarka", "baikonur"));

    private final CatalogService catalog = new CatalogService();
    private final SimulationValidator validator = new SimulationValidator(catalog);

    @Test
    void catalogContainsEveryMeasureUsedByTheIndependentOracle() {
        assertThat(catalog.getDistricts()).extracting(district -> district.id()).containsExactlyElementsOf(DISTRICTS);
        assertThat(catalog.getMeasures()).hasSize(SPECS.size());

        for (int index = 0; index < SPECS.size(); index++) {
            MeasureSpec expected = SPECS.get(index);
            MeasureResponse actual = catalog.getMeasures().get(index);
            double factor = (8.0 - expected.lagQuarters()) / 8.0;
            Map<String, Double> realizedEffects = new LinkedHashMap<>();
            expected.fullEffects().forEach((metric, effect) -> realizedEffects.put(metric, effect * factor));

            assertThat(actual.id()).isEqualTo(expected.id());
            assertThat(actual.categoryId()).isEqualTo(expected.categoryId());
            assertThat(actual.scope()).isEqualTo(expected.scope());
            assertThat(actual.cost()).isEqualTo(expected.cost());
            assertThat(actual.lagQuarters()).isEqualTo(expected.lagQuarters());
            assertThat(actual.realizationFactor()).isEqualTo(factor);
            assertThat(actual.fullEffects()).isEqualTo(expected.fullEffects());
            assertThat(actual.realizedEffects()).isEqualTo(realizedEffects);
        }
    }

    @Test
    void contextualCatalogReturnsAllFourteenMeasuresForEveryDistrict() {
        for (String districtId : DISTRICTS) {
            var options = catalog.getMeasuresForDistrict(districtId);
            assertThat(options).hasSize(14).extracting(option -> option.id())
                    .containsExactlyElementsOf(SPECS.stream().map(MeasureSpec::id).toList());
            options.forEach(option -> {
                if ("city".equals(option.scope())) {
                    assertThat(option.targetDistrictId()).isNull();
                    assertThat(option.affectedDistrictIds()).containsExactlyElementsOf(DISTRICTS);
                } else {
                    assertThat(option.targetDistrictId()).isEqualTo(districtId);
                    assertThat(option.affectedDistrictIds()).containsExactly(districtId);
                }
            });
        }
    }

    @Test
    void validatesEveryCanonicalSelectionStateThroughLogicalEquivalenceClasses() {
        long representedStates = 0;
        long validStates = 0;
        int equivalenceClasses = 0;
        int measureCombinationsWithValidAssignment = 0;
        Map<String, Long> violationIncidence = new TreeMap<>();
        Map<Integer, Long> validBudgetDistribution = new TreeMap<>();

        for (List<MeasureSpec> combination : combinations(SPECS, 5)) {
            boolean combinationHasValidAssignment = false;
            List<LocalConflict> activeLocalConflicts = LOCAL_CONFLICTS.stream()
                    .filter(conflict -> contains(combination, conflict.firstId()) && contains(combination, conflict.secondId()))
                    .toList();

            for (int mask = 0; mask < (1 << activeLocalConflicts.size()); mask++) {
                TargetingClass targeting = targetingClass(combination, activeLocalConflicts, mask);
                List<SimulationValidationException.Violation> violations = violations(request(combination, targeting.targets()));
                Map<String, Long> actualCodes = violationCounts(violations);
                Map<String, Long> expectedCodes = expectedViolationCounts(combination, activeLocalConflicts, mask);

                assertThat(actualCodes)
                        .as("combination=%s targets=%s", ids(combination), targeting.targets())
                        .isEqualTo(expectedCodes);

                representedStates += targeting.multiplicity();
                equivalenceClasses++;
                actualCodes.forEach((code, count) -> violationIncidence.merge(
                        code, count * targeting.multiplicity(), Long::sum));

                if (violations.isEmpty()) {
                    combinationHasValidAssignment = true;
                    validStates += targeting.multiplicity();
                    validBudgetDistribution.merge(cost(combination), targeting.multiplicity(), Long::sum);
                }
            }

            if (combinationHasValidAssignment) {
                measureCombinationsWithValidAssignment++;
            }
        }

        assertThat(combinations(SPECS, 5)).hasSize(2_002);
        assertThat(equivalenceClasses).isEqualTo(2_452);
        assertThat(representedStates).isEqualTo(1_407_050);
        assertThat(validStates).isEqualTo(694_395);
        assertThat(representedStates - validStates).isEqualTo(712_655);
        assertThat(measureCombinationsWithValidAssignment).isEqualTo(1_181);
        assertThat(violationIncidence).containsExactly(
                Map.entry("BUDGET_EXCEEDED", 472_855L),
                Map.entry("CATEGORY_LIMIT", 129_980L),
                Map.entry("CONFLICT", 351_540L));
        assertThat(validBudgetDistribution.keySet()).containsExactlyElementsOf(IntStream.rangeClosed(61, 100).boxed().toList());
        assertThat(validBudgetDistribution.get(100)).isEqualTo(28_575L);
    }

    @Test
    void reportsExactCodesForMalformedAndOverlappingInvalidStates() {
        List<Decision> example = validExample();
        List<InvalidCase> cases = List.of(
                new InvalidCase("null request", null, counts("DECISION_COUNT", 1)),
                new InvalidCase("null decisions", new SimulationRequest(null), counts("DECISION_COUNT", 1)),
                new InvalidCase("four decisions", new SimulationRequest(example.subList(0, 4)), counts("DECISION_COUNT", 1)),
                new InvalidCase("six decisions", new SimulationRequest(List.of(
                        example.get(0), example.get(1), example.get(2), example.get(3), example.get(4), new Decision("M14", null))),
                        counts("DECISION_COUNT", 1)),
                new InvalidCase("null decision", new SimulationRequest(Arrays.asList(
                        null, example.get(1), example.get(2), example.get(3), example.get(4))), counts("MEASURE_REQUIRED", 1)),
                invalidReplacement("null measure", example, 0, new Decision(null, "nura"), counts("MEASURE_REQUIRED", 1)),
                invalidReplacement("blank measure", example, 0, new Decision(" ", "nura"), counts("MEASURE_REQUIRED", 1)),
                invalidReplacement("unknown measure", example, 0, new Decision("M99", "nura"), counts("UNKNOWN_MEASURE", 1)),
                invalidReplacement("duplicate measure", example, 1, new Decision("M7", "nura"), counts("DUPLICATE_MEASURE", 1)),
                invalidReplacement("missing district", example, 0, new Decision("M7", null), counts("DISTRICT_REQUIRED", 1)),
                invalidReplacement("blank district", example, 0, new Decision("M7", " "), counts("DISTRICT_REQUIRED", 1)),
                invalidReplacement("unknown district", example, 0, new Decision("M7", "unknown"), counts("UNKNOWN_DISTRICT", 1)),
                invalidReplacement("city district", example, 3, new Decision("M12", "nura"), counts("CITY_DISTRICT_FORBIDDEN", 1)),
                invalidReplacement("blank city district", example, 3, new Decision("M12", ""), counts("CITY_DISTRICT_FORBIDDEN", 1)),
                new InvalidCase("budget", scenario("M7:nura", "M8:nura", "M3:nura", "M12", "M5:saryarka"),
                        counts("BUDGET_EXCEEDED", 1)),
                new InvalidCase("category", scenario("M7:nura", "M8:nura", "M9:nura", "M12", "M5:saryarka"),
                        counts("CATEGORY_LIMIT", 1)),
                new InvalidCase("global conflict", scenario("M1:nura", "M3:esil", "M9:nura", "M10:nura", "M12"),
                        counts("CONFLICT", 1)),
                new InvalidCase("local conflict", scenario("M4:nura", "M7:nura", "M9:nura", "M10:nura", "M12"),
                        counts("CONFLICT", 1)),
                new InvalidCase("invalid targets are not conflicts", scenario(
                        "M4:unknown", "M7:unknown", "M9:nura", "M10:nura", "M12"), counts("UNKNOWN_DISTRICT", 2)),
                new InvalidCase("blank targets are not conflicts", scenario(
                        "M4:", "M7:", "M9:nura", "M10:nura", "M12"), counts("DISTRICT_REQUIRED", 2)),
                new InvalidCase("duplicate conflict is reported once", scenario(
                        "M4:esil", "M4:esil", "M7:esil", "M10:nura", "M12"),
                        counts("CONFLICT", 1, "DUPLICATE_MEASURE", 1)),
                new InvalidCase("overlapping violations", scenario(
                        "M1:nura", "M2", "M3:esil", "M4:nura", "M7:nura"),
                        counts("BUDGET_EXCEEDED", 1, "CATEGORY_LIMIT", 1, "CONFLICT", 2)));

        cases.forEach(testCase -> assertThat(violationCounts(violations(testCase.request())))
                .as(testCase.name()).isEqualTo(testCase.expectedCodes()));
    }

    @Test
    void acceptsEveryDistrictAndIsIndependentOfDecisionOrder() {
        for (String districtId : DISTRICTS) {
            List<Decision> decisions = new ArrayList<>(validExample());
            decisions.set(0, new Decision("M7", districtId));
            assertThat(violations(new SimulationRequest(decisions))).as(districtId).isEmpty();
        }

        List<Decision> valid = validExample();
        List<Decision> invalid = scenario("M1:nura", "M2", "M3:esil", "M4:nura", "M7:nura").decisions();
        for (List<Decision> permutation : permutations(valid)) {
            assertThat(validator.validate(new SimulationRequest(permutation))).extracting(selected -> selected.measure().id())
                    .containsExactly("M5", "M7", "M8", "M10", "M12");
        }
        for (List<Decision> permutation : permutations(invalid)) {
            assertThat(violationCounts(violations(new SimulationRequest(permutation))))
                    .isEqualTo(counts("BUDGET_EXCEEDED", 1, "CATEGORY_LIMIT", 1, "CONFLICT", 2));
        }
    }

    private List<SimulationValidationException.Violation> violations(SimulationRequest request) {
        try {
            validator.validate(request);
            return List.of();
        } catch (SimulationValidationException exception) {
            return exception.getViolations();
        }
    }

    private static Map<String, Long> expectedViolationCounts(
            List<MeasureSpec> combination,
            List<LocalConflict> activeLocalConflicts,
            int mask) {
        Map<String, Long> result = new TreeMap<>();
        if (cost(combination) > 100) {
            result.put("BUDGET_EXCEEDED", 1L);
        }
        Map<String, Long> categories = new HashMap<>();
        combination.forEach(spec -> categories.merge(spec.categoryId(), 1L, Long::sum));
        if (categories.values().stream().anyMatch(count -> count > 2)) {
            result.put("CATEGORY_LIMIT", 1L);
        }
        long conflicts = contains(combination, "M1") && contains(combination, "M3") ? 1 : 0;
        for (int index = 0; index < activeLocalConflicts.size(); index++) {
            if ((mask & (1 << index)) != 0) {
                conflicts++;
            }
        }
        if (conflicts > 0) {
            result.put("CONFLICT", conflicts);
        }
        return result;
    }

    private static TargetingClass targetingClass(
            List<MeasureSpec> combination,
            List<LocalConflict> activeLocalConflicts,
            int mask) {
        Map<String, String> targets = new HashMap<>();
        combination.stream().filter(spec -> "district".equals(spec.scope()))
                .forEach(spec -> targets.put(spec.id(), "esil"));
        long multiplicity = power(5, (int) combination.stream().filter(spec -> "district".equals(spec.scope())).count()
                - activeLocalConflicts.size() * 2);
        for (int index = 0; index < activeLocalConflicts.size(); index++) {
            LocalConflict conflict = activeLocalConflicts.get(index);
            boolean sameDistrict = (mask & (1 << index)) != 0;
            targets.put(conflict.firstId(), conflict.baseDistrict());
            targets.put(conflict.secondId(), sameDistrict ? conflict.baseDistrict() : conflict.otherDistrict());
            multiplicity *= sameDistrict ? 5 : 20;
        }
        return new TargetingClass(Map.copyOf(targets), multiplicity);
    }

    private static SimulationRequest request(List<MeasureSpec> combination, Map<String, String> targets) {
        return new SimulationRequest(combination.stream()
                .map(spec -> new Decision(spec.id(), "district".equals(spec.scope()) ? targets.get(spec.id()) : null))
                .toList());
    }

    private static List<Decision> validExample() {
        return List.of(new Decision("M7", "nura"), new Decision("M8", "nura"),
                new Decision("M10", "nura"), new Decision("M12", null), new Decision("M5", "saryarka"));
    }

    private static InvalidCase invalidReplacement(
            String name,
            List<Decision> source,
            int index,
            Decision replacement,
            Map<String, Long> expectedCodes) {
        List<Decision> decisions = new ArrayList<>(source);
        decisions.set(index, replacement);
        return new InvalidCase(name, new SimulationRequest(decisions), expectedCodes);
    }

    private static SimulationRequest scenario(String... selections) {
        return new SimulationRequest(Arrays.stream(selections).map(selection -> {
            int separator = selection.indexOf(':');
            return separator < 0
                    ? new Decision(selection, null)
                    : new Decision(selection.substring(0, separator), selection.substring(separator + 1));
        }).toList());
    }

    private static Map<String, Long> violationCounts(List<SimulationValidationException.Violation> violations) {
        Map<String, Long> result = new TreeMap<>();
        violations.forEach(violation -> result.merge(violation.code(), 1L, Long::sum));
        return result;
    }

    private static Map<String, Long> counts(Object... entries) {
        Map<String, Long> result = new TreeMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((String) entries[index], ((Number) entries[index + 1]).longValue());
        }
        return result;
    }

    private static int cost(List<MeasureSpec> combination) {
        return combination.stream().mapToInt(MeasureSpec::cost).sum();
    }

    private static boolean contains(List<MeasureSpec> combination, String measureId) {
        return combination.stream().anyMatch(spec -> spec.id().equals(measureId));
    }

    private static List<String> ids(List<MeasureSpec> combination) {
        return combination.stream().map(MeasureSpec::id).toList();
    }

    private static long power(long base, int exponent) {
        long result = 1;
        for (int index = 0; index < exponent; index++) {
            result *= base;
        }
        return result;
    }

    private static <T> List<List<T>> combinations(List<T> values, int size) {
        List<List<T>> result = new ArrayList<>();
        collectCombinations(values, size, 0, new ArrayList<>(), result);
        return result;
    }

    private static <T> void collectCombinations(
            List<T> values,
            int size,
            int start,
            List<T> current,
            List<List<T>> result) {
        if (current.size() == size) {
            result.add(List.copyOf(current));
            return;
        }
        for (int index = start; index <= values.size() - (size - current.size()); index++) {
            current.add(values.get(index));
            collectCombinations(values, size, index + 1, current, result);
            current.removeLast();
        }
    }

    private static <T> List<List<T>> permutations(List<T> values) {
        List<List<T>> result = new ArrayList<>();
        collectPermutations(new ArrayList<>(values), 0, result);
        return result;
    }

    private static <T> void collectPermutations(List<T> values, int index, List<List<T>> result) {
        if (index == values.size()) {
            result.add(List.copyOf(values));
            return;
        }
        for (int swapIndex = index; swapIndex < values.size(); swapIndex++) {
            java.util.Collections.swap(values, index, swapIndex);
            collectPermutations(values, index + 1, result);
            java.util.Collections.swap(values, index, swapIndex);
        }
    }

    private static MeasureSpec spec(
            String id,
            String categoryId,
            String scope,
            int cost,
            int lagQuarters,
            Object... effects) {
        Map<String, Integer> fullEffects = new LinkedHashMap<>();
        for (int index = 0; index < effects.length; index += 2) {
            fullEffects.put((String) effects[index], (Integer) effects[index + 1]);
        }
        return new MeasureSpec(id, categoryId, scope, cost, lagQuarters, Map.copyOf(fullEffects));
    }

    private record MeasureSpec(
            String id,
            String categoryId,
            String scope,
            int cost,
            int lagQuarters,
            Map<String, Integer> fullEffects) {
    }

    private record LocalConflict(String firstId, String secondId, String baseDistrict, String otherDistrict) {
    }

    private record TargetingClass(Map<String, String> targets, long multiplicity) {
    }

    private record InvalidCase(String name, SimulationRequest request, Map<String, Long> expectedCodes) {
    }
}
