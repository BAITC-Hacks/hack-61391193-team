package astana.innovation.backendakim.simulation;

import java.util.List;

public class SimulationValidationException extends RuntimeException {
    private final List<Violation> violations;

    public SimulationValidationException(List<Violation> violations) {
        super("Набор решений невалиден. Score не рассчитан.");
        this.violations = List.copyOf(violations);
    }

    public List<Violation> getViolations() { return violations; }

    public record Violation(String code, String field, String message) { }
}
