package com.gateway.payment.api.dto;

import java.util.Map;

/**
 * Public payment shape returned by {@code POST /internal/v1/payments} and later by {@code GET
 * /v1/payments/{id}}.
 *
 * <p>Field names are camelCase; the global SNAKE_CASE Jackson strategy serialises them as {@code
 * checkout_session_id}, {@code amount_captured}, etc. on the wire.
 *
 * <p>{@code created}, {@code authorizedAt}, and {@code capturedAt} are Unix epoch seconds (not
 * ISO-8601 strings) to match the API contract in {@code docs/architecture/api.md}.
 */
public record PaymentResponse(
        String id,
        String object,
        String checkoutSessionId,
        long amount,
        long amountCaptured,
        long amountRefunded,
        String currency,
        String status,
        String paymentMethod,
        PaymentMethodDetailsResponse paymentMethodDetails,
        String merchantReference,
        String failureCode,
        String failureMessage,
        long created,
        Long authorizedAt,
        Long capturedAt,
        Map<String, Object> metadata,
        boolean livemode) {

    /** Non-sensitive card metadata nested inside the payment response. */
    public record PaymentMethodDetailsResponse(
            String brand, String last4, Integer expMonth, Integer expYear, String country) {}
}
