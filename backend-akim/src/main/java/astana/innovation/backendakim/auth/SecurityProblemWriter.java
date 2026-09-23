package astana.innovation.backendakim.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class SecurityProblemWriter {
    private final ObjectMapper json;
    public SecurityProblemWriter(ObjectMapper json) { this.json = json; }

    public void unauthorized(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        if (exception instanceof AuthenticationServiceException) {
            write(request, response, HttpStatus.SERVICE_UNAVAILABLE, "Хранилище пользователей недоступно.");
        } else {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            write(request, response, HttpStatus.UNAUTHORIZED, "Требуется действующий токен доступа. Войдите в аккаунт.");
        }
    }

    public void forbidden(HttpServletRequest request, HttpServletResponse response) throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, "Недостаточно прав для этой операции.");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String detail)
            throws IOException {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(status.value());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
    }
}
