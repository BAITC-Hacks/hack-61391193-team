package astana.innovation.backendakim.history;

import astana.innovation.backendakim.simulation.SimulationRequest;
import astana.innovation.backendakim.simulation.SimulationService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("postgres")
@Transactional(transactionManager = "simulationTransactionManager", readOnly = true)
public class SimulationHistoryService {
    private final AnonymousUserRepository users;
    private final SimulationHistoryRepository history;
    private final SimulationService simulation;
    private final TransactionTemplate writes;

    public SimulationHistoryService(AnonymousUserRepository users, SimulationHistoryRepository history,
                                    SimulationService simulation,
                                    @Qualifier("simulationTransactionManager") PlatformTransactionManager transactions) {
        this.users = users;
        this.history = history;
        this.simulation = simulation;
        this.writes = new TransactionTemplate(transactions);
    }

    @Transactional(transactionManager = "simulationTransactionManager")
    public AnonymousUserResponse createAnonymousUser() {
        UUID id = UUID.randomUUID();
        Instant createdAt = now();
        String token = AnonymousTokens.generate();
        users.insert(id, AnonymousTokens.hash(token), createdAt);
        return new AnonymousUserResponse(id, token, "Bearer", createdAt);
    }

    @Transactional(transactionManager = "simulationTransactionManager", propagation = Propagation.NOT_SUPPORTED)
    public SavedSimulationResponse save(UUID userId, SimulationRequest request) {
        var result = simulation.calculate(request);
        var snapshot = new SimulationRequest(List.copyOf(request.decisions()));
        var saved = new SavedSimulationResponse(UUID.randomUUID(), userId, now(), snapshot, result);
        // Calculation and the optional network request must not hold a database connection.
        writes.executeWithoutResult(status -> history.insert(saved));
        return saved;
    }

    public SavedSimulationResponse find(UUID userId, UUID id) {
        return history.findByIdAndUserId(id, userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Сохранённый сценарий не найден."));
    }

    public SimulationHistoryPage list(UUID userId, int limit, long offset) {
        if (limit < 1 || limit > 100 || offset < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit должен быть от 1 до 100, offset — неотрицательным целым числом.");
        }
        var rows = history.findByUserId(userId, limit + 1, offset);
        return new SimulationHistoryPage(List.copyOf(rows.subList(0, Math.min(limit, rows.size()))),
                limit, offset, rows.size() > limit);
    }

    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
}
