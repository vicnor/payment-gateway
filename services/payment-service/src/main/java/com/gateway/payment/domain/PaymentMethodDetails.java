package com.gateway.payment.domain;

/**
 * Non-sensitive card metadata stored in {@code payments.payment_method_details} (JSONB).
 *
 * <p>Populated from the detokenize response (brand/last4/country/funding are plaintext on the token
 * record; the PAN is never stored here — ADR-0004).
 *
 * <p>Field names use camelCase; the global SNAKE_CASE Jackson strategy serializes them as {@code
 * exp_month} and {@code exp_year} when stored in JSONB and when returned in the API response.
 */
public record PaymentMethodDetails(
        String brand, String last4, Integer expMonth, Integer expYear, String country) {}
