package astana.innovation.backendakim.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("postgres")
public class AccountRepository {
    private static final String COLUMNS = "id, email, username, password_hash, role, created_at";
    private static final String INSERT = "INSERT INTO akim.users (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?)";
    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Account> findByEmail(String email) {
        return jdbc.query("SELECT " + COLUMNS + " FROM akim.users WHERE email = ? AND password_hash IS NOT NULL",
                AccountRepository::map, email).stream().findFirst();
    }

    public Optional<Account> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM akim.users WHERE id = ? AND password_hash IS NOT NULL",
                AccountRepository::map, id).stream().findFirst();
    }

    public void insert(Account account) {
        jdbc.update(INSERT, values(account));
    }

    public boolean insertIfAbsent(Account account) {
        return jdbc.update(INSERT + " ON CONFLICT (email) DO NOTHING", values(account)) == 1;
    }

    private static Object[] values(Account account) {
        return new Object[] {account.id(), account.email(), account.username(), account.passwordHash(),
                account.role(), Timestamp.from(account.createdAt())};
    }

    private static Account map(ResultSet row, int index) throws SQLException {
        return new Account(row.getObject("id", UUID.class), row.getString("email"), row.getString("username"),
                row.getString("password_hash"), row.getString("role"), row.getTimestamp("created_at").toInstant());
    }

    public record Account(UUID id, String email, String username, String passwordHash, String role, Instant createdAt) {
        public AuthUserResponse toResponse() {
            return new AuthUserResponse(id, email, username, role, createdAt);
        }

        @Override
        public String toString() {
            return "Account[id=" + id + ", email=" + email + ", username=" + username
                    + ", passwordHash=[REDACTED], role=" + role + ", createdAt=" + createdAt + "]";
        }
    }
}
