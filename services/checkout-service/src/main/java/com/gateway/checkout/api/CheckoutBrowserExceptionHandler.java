package com.gateway.checkout.api;

import com.gateway.shared.web.error.ErrorEnvelope;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = CheckoutBrowserController.class)
public class CheckoutBrowserExceptionHandler {
    @ExceptionHandler(CompletionInProgressException.class)
    ResponseEntity<ErrorEnvelope> completionInProgress(CompletionInProgressException ex) {
        return ResponseEntity.status(409)
                .header("Retry-After", "1")
                .body(
                        ErrorEnvelope.of(
                                "conflict_error",
                                ex.code(),
                                ex.getMessage(),
                                null,
                                MDC.get("requestId")));
    }
}
