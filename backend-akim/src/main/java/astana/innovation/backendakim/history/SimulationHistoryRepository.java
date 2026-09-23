package astana.innovation.backendakim.history;

import astana.innovation.backendakim.simulation.SimulationRequest;
import astana.innovation.backendakim.simulation.SimulationResult;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
@Profile("postgres")
public class SimulationHistoryRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public SimulationHistoryRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(SavedSimulationResponse saved) {
        var result = saved.result();
        jdbc.update("""
                INSERT INTO akim.simulations
                    (id, user_id, created_at, model_version, request, result,
                     final_score, baseline_score, score_delta, budget_spent)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?)
                """, saved.id(), saved.userId(), Timestamp.from(saved.createdAt()), result.modelVersion(),
                json.writeValueAsString(saved.request()), json.writeValueAsString(result),
                result.finalScore(), result.baselineScore(), result.scoreDelta(), result.budget().spent());
    }

    public Optional<SavedSimulationResponse> findByIdAndUserId(UUID id, UUID userId) {
        return jdbc.query("""
                SELECT id, user_id, created_at, request, result
                FROM akim.simulations WHERE id = ? AND user_id = ?
                """, (row, index) -> new SavedSimulationResponse(
                row.getObject("id", UUID.class), row.getObject("user_id", UUID.class),
                row.getTimestamp("created_at").toInstant(),
                json.readValue(row.getString("request"), SimulationRequest.class),
                json.readValue(row.getString("result"), SimulationResult.class)), id, userId)
                .stream().findFirst();
    }

    public List<SimulationHistoryPage.Item> findByUserId(UUID userId, int limit, long offset) {
        return jdbc.query("""
                SELECT id, created_at, model_version, final_score, baseline_score, score_delta, budget_spent
                FROM akim.simulations WHERE user_id = ?
                ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?
                """, (row, index) -> new SimulationHistoryPage.Item(
                row.getObject("id", UUID.class), row.getTimestamp("created_at").toInstant(),
                row.getString("model_version"), row.getBigDecimal("final_score"),
                row.getBigDecimal("baseline_score"), row.getBigDecimal("score_delta"),
                row.getInt("budget_spent")), userId, limit, offset);
    }
}
