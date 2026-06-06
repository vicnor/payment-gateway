package com.gateway.merchant.domain;

public record IssuedApiKey(String plainKey, ApiKey persisted) {}
