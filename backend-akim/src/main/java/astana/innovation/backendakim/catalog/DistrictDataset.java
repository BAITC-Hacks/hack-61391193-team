package astana.innovation.backendakim.catalog;

import astana.innovation.backendakim.simulation.SimulationRules;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

public final class DistrictDataset {
    public static final String MODEL_VERSION = "v2-saraishyk";
    public static final String RESOURCE = "catalog/districts-v2.json";

    private DistrictDataset() { }

    static List<DistrictResponse> load() {
        try {
            byte[] data = new ClassPathResource(RESOURCE).getContentAsByteArray();
            return validate(Arrays.asList(new ObjectMapper().readValue(data, DistrictResponse[].class)));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load district dataset " + RESOURCE, exception);
        }
    }

    static List<DistrictResponse> validate(List<DistrictResponse> districts) {
        var ids = new HashSet<String>();
        BigDecimal populationTotal = BigDecimal.ZERO;
        for (var district : districts) {
            if (!ids.add(district.id()) || district.populationShare() <= 0
                    || district.dataProvenance() == null
                    || !MODEL_VERSION.equals(district.dataProvenance().modelVersion())
                    || !district.metrics().keySet().equals(SimulationRules.METRIC_WEIGHTS.keySet())) {
                throw new IllegalStateException("Invalid district dataset entry: " + district.id());
            }
            BigDecimal score = BigDecimal.ZERO;
            for (var entry : district.metrics().entrySet()) {
                if (entry.getValue() == null || entry.getValue() < 0 || entry.getValue() > 100) {
                    throw new IllegalStateException("District metric must be in 0..100: " + district.id());
                }
                score = score.add(BigDecimal.valueOf(entry.getValue()).multiply(SimulationRules.METRIC_WEIGHTS.get(entry.getKey())));
            }
            if (score.compareTo(BigDecimal.valueOf(district.baselineScore())) != 0) {
                throw new IllegalStateException("District baseline differs from its metrics: " + district.id());
            }
            populationTotal = populationTotal.add(BigDecimal.valueOf(district.populationShare()));
        }
        if (districts.size() != 6 || populationTotal.compareTo(BigDecimal.ONE) != 0) {
            throw new IllegalStateException("The v2 dataset must contain six districts with weights summing to 1");
        }
        return List.copyOf(districts);
    }
}
