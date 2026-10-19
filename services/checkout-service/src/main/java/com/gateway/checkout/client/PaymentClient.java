package com.gateway.checkout.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

public interface PaymentClient {
    PaymentResult create(CreatePayment command);

    java.util.Optional<PaymentResult> find(String attemptId, String sessionId, String merchantId);

    record CreatePayment(
            String completionAttemptId,
            String checkoutSessionId,
            String merchantId,
            long amount,
            String currency,
            String merchantReference,
            String token,
            Map<String, String> metadata) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PaymentResult(String id, String status, String failureCode, String failureMessage) {}
}
