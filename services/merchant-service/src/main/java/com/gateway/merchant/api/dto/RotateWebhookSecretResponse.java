package com.gateway.merchant.api.dto;

import java.time.Instant;
import java.util.UUID;

public record RotateWebhookSecretResponse(UUID id, String secret, Instant createdAt) {}
