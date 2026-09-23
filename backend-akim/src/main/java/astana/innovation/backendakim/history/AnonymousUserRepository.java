package astana.innovation.backendakim.history;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("postgres")
public class AnonymousUserRepository {
    private final JdbcTemplate jdbc;

    public AnonymousUserRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(UUID id, String tokenHash, Instant createdAt) {
        jdbc.update("INSERT INTO akim.users (id, token_hash, created_at) VALUES (?, ?, ?)",
                id, tokenHash, Timestamp.from(createdAt));
    }

    public Optional<UUID> findIdByTokenHash(String tokenHash) {
        return jdbc.query("SELECT id FROM akim.users WHERE token_hash = ? AND email IS NULL",
                (row, index) -> row.getObject("id", UUID.class), tokenHash).stream().findFirst();
    }
}
