package com.gateway.checkout.api.dto;

public record CheckoutSessionResponse(
        String id,
        String object,
        String status,
        String url,
        long amount,
        String currency,
        String merchantReference,
        long expiresAt,
        long created,
        boolean livemode,
        String paymentId) {}
