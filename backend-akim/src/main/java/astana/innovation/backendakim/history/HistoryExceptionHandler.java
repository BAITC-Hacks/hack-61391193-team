package astana.innovation.backendakim.history;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = SimulationHistoryController.class)
@Order(-1)
public class HistoryExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(HistoryExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> requestError(ResponseStatusException exception) {
        var response = ResponseEntity.status(exception.getStatusCode()).cacheControl(CacheControl.noStore());
        if (exception.getStatusCode().value() == 401) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return response.body(exception.getBody());
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ProblemDetail> storageError(RuntimeException exception) {
        LOG.error("Simulation history storage operation failed", exception);
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Не удалось подтвердить операцию с хранилищем. Проверьте историю перед повторным сохранением.");
        problem.setTitle("Хранилище недоступно");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore()).body(problem);
    }
}
