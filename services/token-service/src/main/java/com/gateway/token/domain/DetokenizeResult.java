package com.gateway.token.domain;

/**
 * The decrypted card data returned to the caller of the detokenize endpoint.
 *
 * <p>CVV is not included — it is never persisted (PCI DSS). Holder name is not included — not
 * required by the acquirer for authorization.
 *
 * <p>Card metadata ({@code brand}, {@code last4}, {@code country}, {@code funding}) is read from
 * the plaintext {@code card} attribute on the token record — no additional decryption required.
 * payment-service stores these in {@code payment_method_details} so the PAN never leaves
 * token-service (ADR-0004).
 */
public record DetokenizeResult(
        String pan,
        int expMonth,
        int expYear,
        String brand,
        String last4,
        String country,
        String funding) {}
