package com.etekisi.etekisi.config;

import com.etekisi.etekisi.agent.observability.CorrelationContext;
import com.etekisi.etekisi.web.ApiException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Uniform RFC 7807 (ProblemDetail) error envelope across the API.
 * <p>Goals:
 * <ul>
 *   <li>Stable shape so clients never have to switch on error message strings.</li>
 *   <li>Correlation-id always echoed so log lookups are trivial.</li>
 *   <li>No leakage of stack traces or DB internals into the response body.</li>
 *   <li>Validation errors enumerate the offending fields.</li>
 *   <li>The right status code: 4xx for client mistakes, 500 only for real bugs.</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ProblemDetail onApi(ApiException ex) {
        Map<String, Object> extras = new LinkedHashMap<>(ex.getExtras());
        extras.put("code", ex.getCode());
        return problem(ex.getStatus(),
                ex.getStatus().getReasonPhrase(),
                ex.getMessage(),
                "https://tekisi.dev/problems/" + ex.getCode().toLowerCase().replace('_', '-'),
                extras);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe ->
                fields.put(fe.getField(), fe.getDefaultMessage()));
        return problem(HttpStatus.BAD_REQUEST,
                "Request validation failed",
                "One or more fields failed validation.",
                "https://tekisi.dev/problems/validation-failed",
                Map.of("fieldErrors", fields, "code", "VALIDATION_FAILED"));
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
                Map.of("violations", fields, "code", "VALIDATION_FAILED"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail onUnreadable(HttpMessageNotReadableException ex) {
        // The cause may include the raw class name of the deserialiser — strip
        // that so the body never reveals internal types.
        return problem(HttpStatus.BAD_REQUEST,
                "Malformed request body",
                "Request body could not be parsed.",
                "https://tekisi.dev/problems/malformed-body",
                Map.of("code", "MALFORMED_BODY"));
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ProblemDetail onTooLarge(org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE,
                "File too large",
                "Files can be up to 5 MB. Take the photo again at a lower resolution.",
                "https://tekisi.dev/problems/file-too-large",
                Map.of("code", "FILE_TOO_LARGE"));
    }

    @ExceptionHandler({org.springframework.web.multipart.support.MissingServletRequestPartException.class,
                       org.springframework.web.multipart.MultipartException.class})
    public ProblemDetail onBadUpload(Exception ex) {
        return problem(HttpStatus.BAD_REQUEST,
                "Upload incomplete",
                "Choose a file to upload.",
                "https://tekisi.dev/problems/upload",
                Map.of("code", "FILE_REQUIRED"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail onTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return problem(HttpStatus.BAD_REQUEST,
                "Invalid request parameter",
                "Parameter '" + ex.getName() + "' has an invalid value.",
                "https://tekisi.dev/problems/invalid-parameter",
                Map.of("parameter", ex.getName(), "code", "INVALID_PARAMETER"));
    }

    @ExceptionHandler({AuthenticationException.class, AuthenticationCredentialsNotFoundException.class})
    public ProblemDetail onAuth(AuthenticationException ex) {
        return problem(HttpStatus.UNAUTHORIZED,
                "Authentication required",
                "A valid bearer token is required.",
                "https://tekisi.dev/problems/unauthenticated",
                Map.of("code", "UNAUTHENTICATED"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail onAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN,
                "Access denied",
                "You do not have permission to access this resource.",
                "https://tekisi.dev/problems/access-denied",
                Map.of("code", "ACCESS_DENIED"));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail onNotFound(NoSuchElementException ex) {
        return problem(HttpStatus.NOT_FOUND,
                "Not found",
                "The requested resource does not exist.",
                "https://tekisi.dev/problems/not-found",
                Map.of("code", "NOT_FOUND"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail onDataIntegrity(DataIntegrityViolationException ex) {
        // Usually a unique-constraint race (duplicate email, duplicate wallet).
        // The DB message can name tables and columns, so it is logged, not returned.
        log.warn("data.integrity.violation correlationId={} message={}",
                CorrelationContext.get(), ex.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT,
                "Conflict",
                "The request conflicts with existing data.",
                "https://tekisi.dev/problems/conflict",
                Map.of("code", "CONFLICT"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onIllegalArg(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST,
                "Invalid argument",
                ex.getMessage() != null ? ex.getMessage() : "Invalid argument.",
                "https://tekisi.dev/problems/invalid-argument",
                Map.of("code", "INVALID_ARGUMENT"));
    }

    /**
     * Catch-all. Spring's own web exceptions (ResponseStatusException, missing
     * parameter, unknown route, wrong method, …) implement ErrorResponse and
     * keep their status. Anything else is a bug: logged at ERROR with the
     * correlation-id and returned as a sanitized 500.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail onUnhandled(Exception ex) {
        if (ex instanceof ErrorResponse er) {
            HttpStatusCode status = er.getStatusCode();
            ProblemDetail pd = er.getBody();
            pd.setProperty("correlationId", CorrelationContext.get());
            pd.setProperty("code", HttpStatus.valueOf(status.value()).name());
            if (status.is5xxServerError()) {
                log.error("web.error correlationId={} status={} message={}",
                        CorrelationContext.get(), status.value(), ex.getMessage(), ex);
            }
            return pd;
        }
        log.error("unhandled.exception correlationId={} type={} message={}",
                CorrelationContext.get(), ex.getClass().getSimpleName(), ex.getMessage(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error",
                "An unexpected error occurred. The incident has been logged.",
                "https://tekisi.dev/problems/internal-error",
                Map.of("code", "INTERNAL_ERROR"));
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
