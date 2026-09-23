package astana.innovation.backendakim.auth;

import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = {AuthController.class, AdminController.class})
@Order(-1)
public class AuthExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> requestError(ResponseStatusException exception) {
        var response = ResponseEntity.status(exception.getStatusCode()).cacheControl(CacheControl.noStore());
        if (exception.getStatusCode().value() == 401) response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return response.body(exception.getBody());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validationError(MethodArgumentNotValidException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Проверьте поля запроса.");
        List<FieldViolation> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage())).toList();
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(problem);
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ProblemDetail> storageError() {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Хранилище пользователей недоступно.");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore()).body(problem);
    }

    public record FieldViolation(String field, String message) { }
}
