package com.gateway.merchant.domain;

public record RotatedSecret(String plainSecret, WebhookSecret persisted) {}
