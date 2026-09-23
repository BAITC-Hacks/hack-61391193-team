package astana.innovation.backendakim.catalog;

import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
@Profile("postgres")
public class DistrictRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public DistrictRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<DistrictResponse> findCurrent() {
        return DistrictDataset.validate(jdbc.query("""
                SELECT jsonb_build_object('id', id, 'name', name, 'populationShare', population_share,
                    'metrics', metrics, 'baselineScore', baseline_score, 'dataProvenance', data_provenance) AS payload
                FROM akim.districts
                WHERE model_version = ? ORDER BY sort_order
                """, (row, index) -> json.readValue(row.getString("payload"), DistrictResponse.class),
                DistrictDataset.MODEL_VERSION));
    }
}
