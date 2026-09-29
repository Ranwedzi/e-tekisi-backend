package com.etekisi.etekisi.web;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Domain error with an HTTP status and a stable machine-readable {@code code}.
 * {@code GlobalExceptionHandler} renders it as a ProblemDetail, adding
 * {@code code} and any {@code extras} as top-level properties.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> extras;

    public ApiException(HttpStatus status, String code, String detail, Map<String, Object> extras) {
        super(detail);
        this.status = status;
        this.code = code;
        this.extras = extras == null ? Map.of() : extras;
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " not found", null);
    }

    public static ApiException badRequest(String code, String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, detail, null);
    }

    public static ApiException conflict(String code, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, detail, null);
    }

    public static ApiException conflict(String code, String detail, Map<String, Object> extras) {
        return new ApiException(HttpStatus.CONFLICT, code, detail, extras);
    }

    public static ApiException forbidden(String code, String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, code, detail, null);
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public Map<String, Object> getExtras() { return extras; }
}
