package com.gateway.token.api.dto;

public record DetokenizeRequest(String checkoutSessionId, String merchantId) {}
