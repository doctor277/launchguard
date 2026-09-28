package io.github.doctor277.launchguard.web;

import io.github.doctor277.launchguard.service.CheckAlreadyInProgressException;
import io.github.doctor277.launchguard.service.DuplicateServiceNameException;
import io.github.doctor277.launchguard.service.DeploymentNotFoundException;
import io.github.doctor277.launchguard.service.DeploymentExternalIdConflictException;
import io.github.doctor277.launchguard.service.ServiceNotFoundException;
import io.github.doctor277.launchguard.service.IncidentNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(io.github.doctor277.launchguard.messaging.ProbeDispatchException.class)
    ResponseEntity<ApiError> handleDispatchUnavailable(RuntimeException exception, HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler({ServiceNotFoundException.class, DeploymentNotFoundException.class, IncidentNotFoundException.class})
    ResponseEntity<ApiError> handleNotFound(RuntimeException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler({DuplicateServiceNameException.class, CheckAlreadyInProgressException.class,
            DeploymentExternalIdConflictException.class, DataIntegrityViolationException.class})
    ResponseEntity<ApiError> handleConflict(RuntimeException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception,
                                               HttpServletRequest request) {
        List<FieldViolation> violations = exception.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new FieldViolation(fieldError.getField(), fieldError.getDefaultMessage()))
                .toList();
        return error(HttpStatus.BAD_REQUEST, "Request validation failed", request, violations);
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiError> handleBadRequest(RuntimeException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("unexpected_request_failure method={} path={}", request.getMethod(), request.getRequestURI(), exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request, List.of());
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String message,
                                                   HttpServletRequest request,
                                                   List<FieldViolation> violations) {
        ApiError body = new ApiError(Instant.now(), status.value(), status.getReasonPhrase(),
                message, request.getRequestURI(), violations);
        return ResponseEntity.status(status).body(body);
    }
}
