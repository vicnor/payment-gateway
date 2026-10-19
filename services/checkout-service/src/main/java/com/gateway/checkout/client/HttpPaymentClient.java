package com.gateway.checkout.client;

import com.gateway.shared.web.error.ConflictException;
import com.gateway.shared.web.error.ServiceUnavailableException;
import com.gateway.shared.web.error.ValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

public final class HttpPaymentClient implements PaymentClient {
    private final RestClient client;

    public HttpPaymentClient(RestClient client) {
        this.client = client;
    }

    @Override
    public java.util.Optional<PaymentResult> find(
            String attemptId, String sessionId, String merchantId) {
        try {
            PaymentResult result =
                    client.get()
                            .uri(
                                    builder ->
                                            builder.path("/internal/v1/payment-attempts/{id}")
                                                    .queryParam("checkout_session_id", sessionId)
                                                    .queryParam("merchant_id", merchantId)
                                                    .build(attemptId))
                            .retrieve()
                            .body(PaymentResult.class);
            if (result == null) throw unavailable();
            return java.util.Optional.of(result);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) return java.util.Optional.empty();
            throw unavailable();
        } catch (RestClientException ex) {
            throw unavailable();
        }
    }

    @Override
    public PaymentResult create(CreatePayment command) {
        try {
            PaymentResult result =
                    client.post()
                            .uri("/internal/v1/payments")
                            .body(command)
                            .retrieve()
                            .body(PaymentResult.class);
            if (result == null) throw new RestClientException("empty payment response");
            return result;
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode() == HttpStatus.CONFLICT) {
                String body = ex.getResponseBodyAsString();
                if (body.contains("token_session_mismatch"))
                    throw new ConflictException(
                            "token_session_mismatch",
                            "Token does not belong to this checkout session.");
                if (body.contains("token_already_used"))
                    throw new ConflictException(
                            "token_already_used", "Token has already been used.");
                throw new ConflictException(
                        "invalid_token", "Token cannot be used for this checkout session.");
            }
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND
                    || ex.getStatusCode().is4xxClientError()) {
                throw new ValidationException(
                        "invalid_token", "Token is invalid or expired.", "token");
            }
            throw unavailable();
        } catch (RestClientException ex) {
            throw unavailable();
        }
    }

    private static ServiceUnavailableException unavailable() {
        return new ServiceUnavailableException(
                "payment_service_unavailable", "Payment processing is temporarily unavailable.");
    }
}
