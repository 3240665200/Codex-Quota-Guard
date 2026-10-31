package com.pingche.web.common;

import com.pingche.web.auth.DomainException;
import jakarta.validation.ConstraintViolationException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> uploadTooLarge() {
        return response(HttpStatus.PAYLOAD_TOO_LARGE, "UPLOAD_TOO_LARGE", "上传文件过大；桌面安装包须不超过 512 MiB。", List.of());
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final AtomicLong LAST_METER_REJECTION_LOG = new AtomicLong();
    private static final long METER_REJECTION_LOG_INTERVAL_MILLIS = 300_000L;
    private static final String METER_USAGE_PATH = "/api/v1/meter/usage/events";

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiError> domain(DomainException exception, HttpServletRequest request) {
        logMeterRejection(request, exception.getCode(), List.of());
        return response(
                exception.getStatus(),
                exception.getCode(),
                exception.getMessage(),
                List.of()
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        List<ApiError.FieldViolation> details = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> new ApiError.FieldViolation(
                        error.getField(),
                        error.getDefaultMessage()
                ))
                .toList();
        logMeterRejection(request, "VALIDATION_ERROR", details);
        return response(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Request validation failed",
                details
        );
    }

    @ExceptionHandler({
            HandlerMethodValidationException.class,
            ConstraintViolationException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiError> requestParameterValidation(Exception exception) {
        return response(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Request validation failed",
                List.of()
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> malformedJson(HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        logMeterRejection(request, "MALFORMED_JSON", List.of());
        return response(
                HttpStatus.BAD_REQUEST,
                "MALFORMED_JSON",
                "Request body is not valid JSON",
                List.of()
        );
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> methodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .headers(exception.getHeaders())
                .body(new ApiError(
                        "METHOD_NOT_ALLOWED",
                        "Request method is not supported for this endpoint",
                        UUID.randomUUID().toString(),
                        List.of()
                ));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> notFound(NoResourceFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "NOT_FOUND", "Endpoint not found", List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(
            Exception exception,
            HttpServletRequest request
    ) {
        String requestId = UUID.randomUUID().toString();
        LOGGER.error(
                "Unexpected API error requestId={} method={} path={}",
                requestId,
                request.getMethod(),
                request.getRequestURI(),
                exception
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                new ApiError(
                        "INTERNAL_ERROR",
                        "Internal server error",
                        requestId,
                        List.of()
                )
        );
    }

    private ResponseEntity<ApiError> response(
            HttpStatus status,
            String code,
            String message,
            List<ApiError.FieldViolation> details
    ) {
        return ResponseEntity.status(status).body(new ApiError(
                code,
                message,
                UUID.randomUUID().toString(),
                details
        ));
    }

    private void logMeterRejection(HttpServletRequest request, String code,
            List<ApiError.FieldViolation> details) {
        if (!METER_USAGE_PATH.equals(request.getRequestURI())) return;
        long now = System.currentTimeMillis();
        long previous = LAST_METER_REJECTION_LOG.get();
        if (now - previous < METER_REJECTION_LOG_INTERVAL_MILLIS
                || !LAST_METER_REJECTION_LOG.compareAndSet(previous, now)) return;
        LOGGER.warn("Meter usage rejected code={} fields={}", code,
                details.stream().map(ApiError.FieldViolation::field).toList());
    }
}
