package astana.innovation.backendakim.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("postgres")
public class AdminAccountInitializer implements ApplicationRunner {
    private final AccountRepository accounts;
    private final PasswordEncoder passwords;
    private final String email;
    private final String password;
    private final String username;

    public AdminAccountInitializer(AccountRepository accounts, PasswordEncoder passwords,
            @Value("${app.auth.admin.email:admin@example.com}") String email,
            @Value("${app.auth.admin.password:Admin}") String password,
            @Value("${app.auth.admin.username:admin}") String username) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.email = email.strip().toLowerCase(Locale.ROOT);
        this.password = password;
        this.username = username.strip();
    }

    @Override
    @Transactional(transactionManager = "simulationTransactionManager")
    public void run(ApplicationArguments arguments) {
        if (email.isBlank() || email.length() > 254 || !email.contains("@")
                || username.isBlank() || username.length() < 3 || username.length() > 50
                || password.isBlank() || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("Invalid administrator account configuration.");
        }
        var existing = accounts.findByEmail(email);
        if (existing.isPresent()) {
            requireAdmin(existing.get());
            return;
        }
        var administrator = new AccountRepository.Account(UUID.randomUUID(), email, username,
                passwords.encode(password), "ADMIN", Instant.now().truncatedTo(ChronoUnit.MICROS));
        accounts.insertIfAbsent(administrator);
        // Another application instance may have created this email in parallel.
        requireAdmin(accounts.findByEmail(email).orElseThrow(() ->
                new IllegalStateException("Administrator account could not be initialized.")));
    }

    private static void requireAdmin(AccountRepository.Account account) {
        if (!"ADMIN".equals(account.role())) {
            throw new IllegalStateException("Configured administrator email belongs to a non-administrator account.");
        }
    }
}
