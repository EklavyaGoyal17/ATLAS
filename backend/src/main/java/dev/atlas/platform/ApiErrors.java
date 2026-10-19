package dev.atlas.platform;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrors {

    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> domain(ApiException ex) {
        return error(ex.status.value(), ex.code, ex.getMessage());
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        MissingRequestHeaderException.class,
        MissingServletRequestParameterException.class,
    })
    ResponseEntity<?> validation(Exception ex) {
        return error(
            400,
            "INVALID_REQUEST",
            "Check required fields, integer minor units, currency, and idempotency key."
        );
    }

    @ExceptionHandler(
        org.springframework.dao.TransientDataAccessException.class
    )
    ResponseEntity<?> busy(Exception ex) {
        return error(
            503,
            "TEMPORARILY_UNAVAILABLE",
            "Database busy. Retry the same request with its original idempotency key."
        );
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception ex) {
        log.error(
            "request_failed type={} frames={}",
            ex.getClass().getSimpleName(),
            java.util.Arrays.stream(ex.getStackTrace())
                .limit(8)
                .map(StackTraceElement::toString)
                .toList()
        );
        return error(
            500,
            "INTERNAL_ERROR",
            "Request failed. Use the correlation ID to investigate."
        );
    }

    private ResponseEntity<?> error(int status, String code, String message) {
        return ResponseEntity.status(status).body(
            Map.of(
                "code",
                code,
                "message",
                message,
                "correlationId",
                RequestContext.correlationId().toString()
            )
        );
    }
}
