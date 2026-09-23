package astana.innovation.backendakim.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("postgres")
@Transactional(transactionManager = "simulationTransactionManager", readOnly = true)
public class AuthService {
    private final AccountRepository accounts;
    private final PasswordEncoder passwords;
    private final JwtTokenService tokens;
    private final String adminEmail;
    private final String dummyPasswordHash;

    public AuthService(AccountRepository accounts, PasswordEncoder passwords, JwtTokenService tokens,
            @Value("${app.auth.admin.email:admin@example.com}") String adminEmail) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.tokens = tokens;
        this.adminEmail = adminEmail.strip().toLowerCase(Locale.ROOT);
        // Unknown accounts still perform a password verification.
        this.dummyPasswordHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional(transactionManager = "simulationTransactionManager")
    public AuthResponse register(RegisterRequest request) {
        if (request == null || request.email() == null || request.email().isBlank()
                || request.email().length() > 254 || request.username() == null
                || request.username().isBlank() || request.username().length() < 3 || request.username().length() > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите корректные email и username.");
        }
        if (request.password() == null || request.password().isBlank() || request.password().length() < 8
                || request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Пароль должен содержать минимум 8 символов и занимать не больше 72 байт UTF-8.");
        }
        if (adminEmail.equals(request.email()) || accounts.findByEmail(request.email()).isPresent()) {
            throw emailTaken();
        }
        var account = new AccountRepository.Account(UUID.randomUUID(), request.email(), request.username(),
                passwords.encode(request.password()), "USER", Instant.now().truncatedTo(ChronoUnit.MICROS));
        try {
            accounts.insert(account);
        } catch (DuplicateKeyException exception) {
            // A concurrent registration may have inserted the same email after the lookup.
            throw emailTaken();
        }
        return tokens.issue(account.toResponse());
    }

    public AuthResponse login(LoginRequest request) {
        if (request == null || request.email() == null || request.email().isBlank()
                || request.password() == null || request.password().isBlank()
                || request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw invalidCredentials();
        }
        var account = accounts.findByEmail(request.email());
        String hash = account.map(AccountRepository.Account::passwordHash).orElse(dummyPasswordHash);
        boolean passwordMatches = passwords.matches(request.password(), hash);
        if (account.isEmpty() || !passwordMatches) {
            throw invalidCredentials();
        }
        return tokens.issue(account.get().toResponse());
    }

    public AuthUserResponse currentUser(UUID userId) {
        return accounts.findById(userId).map(AccountRepository.Account::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Учётная запись не найдена. Выполните вход заново."));
    }

    private static ResponseStatusException emailTaken() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Этот email уже занят.");
    }

    private static ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Неверный email или пароль.");
    }
}
