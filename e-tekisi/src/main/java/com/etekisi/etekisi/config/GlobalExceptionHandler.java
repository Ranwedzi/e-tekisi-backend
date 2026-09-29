package com.etekisi.etekisi.config;

import com.etekisi.etekisi.agent.observability.CorrelationContext;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Uniform RFC 7807 (ProblemDetail) error envelope across the API.
 * <p>Goals:
 * <ul>
 *   <li>Stable shape so clients never have to switch on error message strings.</li>
 *   <li>Correlation-id always echoed so log lookups are trivial.</li>
 *   <li>No leakage of stack traces or DB internals into the response body.</li>
 *   <li>Validation errors enumerate the offending fields.</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe ->
                fields.put(fe.getField(), fe.getDefaultMessage()));
        return problem(HttpStatus.BAD_REQUEST,
                "Request validation failed",
                "One or more fields failed validation.",
                "https://tekisi.dev/problems/validation-failed",
                Map.of("fieldErrors", fields));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail onConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(v ->
                fields.put(v.getPropertyPath().toString(), v.getMessage()));
        return problem(HttpStatus.BAD_REQUEST,
                "Constraint violation",
                "A constraint on the request was violated.",
                "https://tekisi.dev/problems/constraint-violation",
                Map.of("violations", fields));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail onUnreadable(HttpMessageNotReadableException ex) {
        // The cause may include the raw class name of the deserialiser — strip
        // that so the body never reveals internal types.
        return problem(HttpStatus.BAD_REQUEST,
                "Malformed request body",
                "Request body could not be parsed.",
                "https://tekisi.dev/problems/malformed-body",
                Map.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail onTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return problem(HttpStatus.BAD_REQUEST,
                "Invalid request parameter",
                "Parameter '" + ex.getName() + "' has an invalid value.",
                "https://tekisi.dev/problems/invalid-parameter",
                Map.of("parameter", ex.getName()));
    }

    @ExceptionHandler({AuthenticationException.class, AuthenticationCredentialsNotFoundException.class})
    public ProblemDetail onAuth(AuthenticationException ex) {
        return problem(HttpStatus.UNAUTHORIZED,
                "Authentication required",
                "A valid bearer token is required.",
                "https://tekisi.dev/problems/unauthenticated",
                Map.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail onAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN,
                "Access denied",
                "You do not have permission to access this resource.",
                "https://tekisi.dev/problems/access-denied",
                Map.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onIllegalArg(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST,
                "Invalid argument",
                ex.getMessage() != null ? ex.getMessage() : "Invalid argument.",
                "https://tekisi.dev/problems/invalid-argument",
                Map.of());
    }

    /**
     * Catch-all. Logs at ERROR with correlation-id; returns a sanitized 500.
     * Stack traces never reach the wire.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail onUnhandled(Exception ex) {
        log.error("unhandled.exception correlationId={} type={} message={}",
                CorrelationContext.get(), ex.getClass().getSimpleName(), ex.getMessage(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error",
                "An unexpected error occurred. The incident has been logged.",
                "https://tekisi.dev/problems/internal-error",
                Map.of());
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail,
                                  String typeUri, Map<String, ?> extras) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        pd.setType(URI.create(typeUri));
        pd.setProperty("correlationId", CorrelationContext.get());
        extras.forEach(pd::setProperty);
        return pd;
    }
}
