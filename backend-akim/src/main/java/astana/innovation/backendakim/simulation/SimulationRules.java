package astana.innovation.backendakim.simulation;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Rules transcribed from docs1/Датасет районов.docx. */
public final class SimulationRules {
    public static final int BUDGET = 100;
    public static final int DECISIONS = 5;
    public static final int HORIZON = 8;
    public static final int MAX_PER_CATEGORY = 2;
    public static final BigDecimal CRITICAL_THRESHOLD = new BigDecimal("40");
    public static final BigDecimal CITY_WEIGHT = new BigDecimal("0.7");
    public static final BigDecimal WEAKEST_WEIGHT = new BigDecimal("0.3");
    public static final String FORMULA = "0.7 * D_avg + 0.3 * D_min - N_crit";
    public static final Map<String, BigDecimal> METRIC_WEIGHTS;
    static {
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        String[] metrics = {"T1", "T2", "E1", "E2", "S1", "S2", "B1", "B2", "C1", "C2"};
        String[] values = {"0.10", "0.10", "0.09", "0.11", "0.11", "0.11", "0.09", "0.09", "0.10", "0.10"};
        for (int i = 0; i < metrics.length; i++) weights.put(metrics[i], new BigDecimal(values[i]));
        METRIC_WEIGHTS = Collections.unmodifiableMap(weights);
    }
    public static final List<SynergyRule> SYNERGIES = List.of(
            new SynergyRule("M1", "M2", "T1", 2),
            new SynergyRule("M10", "M12", "B1", 2),
            new SynergyRule("M5", "M6", "E2", 2));
    public static final List<ConflictRule> CONFLICTS = List.of(
            new ConflictRule("M1", "M3", false, "BRT и ЛРТ несовместимы даже в разных районах"),
            new ConflictRule("M4", "M7", true, "Парк и школа конфликтуют за участок в одном районе"),
            new ConflictRule("M5", "M13", true, "Чистое топливо и модернизация сетей дублируют программу в одном районе"));

    private SimulationRules() { }

    public record SynergyRule(String districtMeasureId, String cityMeasureId, String metric, int bonus) { }
    public record ConflictRule(String firstMeasureId, String secondMeasureId, boolean sameDistrictOnly, String reason) { }
}
