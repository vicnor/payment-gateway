package com.gateway.token.domain;

/**
 * Internal result of a successful tokenize operation.
 *
 * <p>Contains only the five fields allowed to leave token-service per the PCI response restriction
 * in {@code services/token-service/CLAUDE.md}. No PAN, CVV, holder name, or encryption artifacts.
 */
public record TokenResult(String token, String brand, String last4, int expMonth, int expYear) {}
