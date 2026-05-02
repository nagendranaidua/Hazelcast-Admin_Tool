package com.genius.hz.admin.web;

import com.genius.hz.api.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> auth(AuthenticationException e, HttpServletRequest r) {
        return build(HttpStatus.UNAUTHORIZED, "AUTH_FAILED", e.getMessage(), r);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> denied(AccessDeniedException e, HttpServletRequest r) {
        return build(HttpStatus.FORBIDDEN, "AUTH_DENIED", e.getMessage(), r);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> bad(IllegalArgumentException e, HttpServletRequest r) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage(), r);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> state(IllegalStateException e, HttpServletRequest r) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "CLUSTER_UNREACHABLE", e.getMessage(), r);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> any(Exception e, HttpServletRequest r) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", e.getMessage(), r);
    }

    private static ResponseEntity<ApiError> build(HttpStatus s, String code, String msg, HttpServletRequest r) {
        return ResponseEntity.status(s).body(ApiError.builder()
            .timestamp(Instant.now())
            .status(s.value())
            .code(code)
            .message(msg)
            .path(r.getRequestURI())
            .traceId(UUID.randomUUID().toString())
            .build());
    }
}
