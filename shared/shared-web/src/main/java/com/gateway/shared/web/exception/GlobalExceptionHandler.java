package com.gateway.shared.web.exception;

import com.gateway.shared.web.error.ApiException;
import com.gateway.shared.web.error.ErrorEnvelope;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ErrorEnvelope> handleApiException(ApiException ex) {
    return ResponseEntity.status(ex.status())
        .body(ErrorEnvelope.of(ex.type(), ex.code(), ex.getMessage(), ex.param(), requestId()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorEnvelope> handleValidation(MethodArgumentNotValidException ex) {
    FieldError first = ex.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
    String param = first != null ? first.getField() : null;
    String message = first != null ? first.getDefaultMessage() : "Validation failed.";
    return ResponseEntity.badRequest()
        .body(ErrorEnvelope.of("validation_error", "invalid_request", message, param, requestId()));
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ErrorEnvelope> handleConstraintViolation(ConstraintViolationException ex) {
    var first = ex.getConstraintViolations().stream().findFirst();
    String param = first.map(v -> v.getPropertyPath().toString()).orElse(null);
    String message = first.map(v -> v.getMessage()).orElse("Validation failed.");
    return ResponseEntity.badRequest()
        .body(ErrorEnvelope.of("validation_error", "invalid_request", message, param, requestId()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorEnvelope> handleNotReadable(HttpMessageNotReadableException ex) {
    return ResponseEntity.badRequest()
        .body(
            ErrorEnvelope.of(
                "validation_error",
                "invalid_json",
                "Request body is missing or malformed.",
                null,
                requestId()));
  }

  @ExceptionHandler(Throwable.class)
  public ResponseEntity<ErrorEnvelope> handleUnexpected(Throwable ex) {
    log.error("Unhandled exception [requestId={}]", requestId(), ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(
            ErrorEnvelope.of(
                "api_error", "internal_server_error", "Internal server error.", null, requestId()));
  }

  private static String requestId() {
    return MDC.get("requestId");
  }
}
