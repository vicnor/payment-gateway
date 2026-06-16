package com.gateway.token.api.dto;

/**
 * Response body for the tokenize endpoint.
 *
 * <p>Contains exactly the five fields permitted to leave token-service per the PCI response
 * restriction in {@code services/token-service/CLAUDE.md}. All other card data (PAN, CVV, holder
 * name) must never appear here.
 *
 * <p>Field names are camelCase; Jackson's {@code SNAKE_CASE} strategy serialises them as {@code
 * exp_month} and {@code exp_year} in the JSON response.
 */
public record TokenizeResponse(
        String token, String brand, String last4, int expMonth, int expYear) {}
