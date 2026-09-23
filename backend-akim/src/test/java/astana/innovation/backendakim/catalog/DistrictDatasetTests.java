package astana.innovation.backendakim.catalog;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DistrictDatasetTests {
    @Test
    void sixDistrictDatasetSplitsTheOldAlmatyWeightAndExplainsSyntheticSaraishykMetrics() {
        var districts = DistrictDataset.load();
        assertThat(districts).extracting(DistrictResponse::id)
                .containsExactly("esil", "almaty", "saryarka", "baikonur", "nura", "saraishyk");
        assertThat(districts.stream().map(d -> BigDecimal.valueOf(d.populationShare())).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(BigDecimal.ONE);
        var almaty = districts.get(1);
        var saraishyk = districts.get(5);
        assertThat(BigDecimal.valueOf(almaty.populationShare()).add(BigDecimal.valueOf(saraishyk.populationShare())))
                .isEqualByComparingTo("0.24");
        assertThat(saraishyk.populationShare()).isEqualTo(0.113710);
        assertThat(saraishyk.baselineScore()).isEqualTo(54.23);
        assertThat(saraishyk.metrics()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "T1", 45, "T2", 60, "E1", 55, "E2", 60, "S1", 48,
                "S2", 50, "B1", 60, "B2", 50, "C1", 55, "C2", 60));
        var provenance = saraishyk.dataProvenance();
        assertThat(provenance.modelVersion()).isEqualTo("v2-saraishyk");
        assertThat(provenance.syntheticMetrics()).isTrue();
        assertThat(provenance.metricAssumptions()).containsOnlyKeys(saraishyk.metrics().keySet());
        assertThat(provenance.metricAssumptions().values()).allSatisfy(value -> assertThat(value).isNotBlank());
        assertThat(provenance.facts()).isNotEmpty();
        assertThat(provenance.sources()).isNotEmpty();
        var sourceIds = provenance.sources().stream().map(DistrictProvenance.Source::id).toList();
        provenance.facts().forEach(fact -> assertThat(sourceIds).contains(fact.sourceId()));
        districts.forEach(d -> assertThat(d.dataProvenance().modelVersion()).isEqualTo("v2-saraishyk"));
    }

    @Test
    void rejectsAnUnnormalizedWeightAndAnInconsistentMetricBaseline() {
        var districts = new ArrayList<>(DistrictDataset.load());
        var saraishyk = districts.get(5);
        districts.set(5, new DistrictResponse(saraishyk.id(), saraishyk.name(), 0.12,
                saraishyk.metrics(), saraishyk.baselineScore(), saraishyk.dataProvenance()));
        assertThatThrownBy(() -> DistrictDataset.validate(districts)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("weights summing to 1");

        var changed = new LinkedHashMap<>(saraishyk.metrics());
        changed.put("T1", 46);
        districts.set(5, new DistrictResponse(saraishyk.id(), saraishyk.name(), saraishyk.populationShare(),
                changed, saraishyk.baselineScore(), saraishyk.dataProvenance()));
        assertThatThrownBy(() -> DistrictDataset.validate(districts)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("baseline differs");
    }
}
