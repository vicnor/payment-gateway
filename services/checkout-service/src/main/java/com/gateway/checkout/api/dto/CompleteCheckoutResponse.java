package com.gateway.checkout.api.dto;

public record CompleteCheckoutResponse(
        String status,
        String paymentId,
        String failureCode,
        String failureMessage,
        Boolean retryAllowed,
        String redirectUrl) {}
