package com.gateway.payment.client;

/**
 * Card data returned by token-service's detokenize endpoint.
 *
 * <p>The PAN is held only transiently in memory during the synchronous authorization flow. It is
 * never logged or persisted by payment-service. Brand, last4, country, and funding are
 * non-sensitive and are stored in {@code payment_method_details} (ADR-0004).
 */
public record DetokenizeData(
        String pan,
        int expMonth,
        int expYear,
        String brand,
        String last4,
        String country,
        String funding) {}
