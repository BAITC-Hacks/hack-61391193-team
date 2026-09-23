package astana.innovation.backendakim.simulation;

import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class SimulationExceptionHandler {
    @ExceptionHandler(SimulationValidationException.class)
    ProblemDetail invalidScenario(SimulationValidationException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(422), exception.getMessage());
        problem.setTitle("Невалидный сценарий");
        problem.setProperty("errors", exception.getViolations());
        return problem;
    }
}
