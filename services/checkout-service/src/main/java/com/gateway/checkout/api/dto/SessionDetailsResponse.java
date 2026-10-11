package com.gateway.checkout.api.dto;

import java.util.List;

public record SessionDetailsResponse(
        String sessionId,
        String status,
        long amount,
        String currency,
        String description,
        Merchant merchant,
        List<String> availablePaymentMethods,
        String locale,
        long expiresAt) {
    public record Merchant(String name, String logoUrl, String accentColor) {}
}
