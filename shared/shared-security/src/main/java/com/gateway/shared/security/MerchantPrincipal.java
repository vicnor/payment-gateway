package com.gateway.shared.security;

public record MerchantPrincipal(String merchantId, String apiKeyId, KeyMode keyMode) {}
