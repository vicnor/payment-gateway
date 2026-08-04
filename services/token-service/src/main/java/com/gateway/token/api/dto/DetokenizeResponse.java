package com.gateway.token.api.dto;

/**
 * Response returned by the detokenize endpoint.
 *
 * <p>Contains PAN, expiry, and non-sensitive card metadata (brand, last4, country, funding). CVV is
 * never included — it is not persisted after validation (PCI DSS).
 *
 * <p>payment-service uses {@code brand}, {@code last4}, {@code country}, and {@code funding} to
 * populate {@code payment_method_details} so the PAN never needs to leave token-service.
 */
public record DetokenizeResponse(
        String pan,
        int expMonth,
        int expYear,
        String brand,
        String last4,
        String country,
        String funding) {}
