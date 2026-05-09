package limhjun.me.shortly.config;

import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import limhjun.me.shortly.url.InvalidUrlException;
import limhjun.me.shortly.url.ShortUrlNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Clock;
import java.time.Instant;

@RestControllerAdvice
@Slf4j
public class ApiErrorAdvice {

    private final Tracer tracer;
    private final Clock clock;

    // Tracer is optional: no tracing bridge (micrometer-tracing-bridge-brave/otel)
    // is on the classpath, so Spring Boot won't auto-configure the bean.
    // Making it @Autowired(required = false) prevents NoSuchBeanDefinitionException
    // at startup while still using the trace ID when a bridge is present.
    public ApiErrorAdvice(@Autowired(required = false) Tracer tracer, Clock clock) {
        this.tracer = tracer;
        this.clock = clock;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException e, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "validation_error", e.getMessage(), req);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> malformedJson(HttpMessageNotReadableException e, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "malformed_request", "request body is not valid JSON", req);
    }

    @ExceptionHandler(InvalidUrlException.class)
    ResponseEntity<ErrorResponse> invalidUrl(InvalidUrlException e, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "validation_error", e.getMessage(), req);
    }

    @ExceptionHandler(ShortUrlNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(ShortUrlNotFoundException e, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, "not_found", "short URL not found", req);
    }

    @ExceptionHandler(DataAccessResourceFailureException.class)
    ResponseEntity<ErrorResponse> dbDown(DataAccessResourceFailureException e, HttpServletRequest req) {
        log.error("database unreachable", e);
        return build(HttpStatus.SERVICE_UNAVAILABLE, "service_unavailable", "database temporarily unavailable", req);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> generic(Exception e, HttpServletRequest req) {
        log.error("unhandled exception", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "internal error", req);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String type, String message, HttpServletRequest req) {
        String traceId = (tracer != null && tracer.currentSpan() != null)
                ? tracer.currentSpan().context().traceId()
                : null;
        return ResponseEntity.status(status).body(new ErrorResponse(
                type, message, Instant.now(clock), req.getRequestURI(), traceId));
    }
}
