package com.gateway.merchant.api.mapper;

import com.gateway.merchant.api.dto.RotateWebhookSecretResponse;
import com.gateway.merchant.domain.RotatedSecret;

public final class WebhookSecretMapper {

    private WebhookSecretMapper() {}

    public static RotateWebhookSecretResponse toResponse(RotatedSecret rotated) {
        return new RotateWebhookSecretResponse(
                rotated.persisted().getId(),
                rotated.plainSecret(),
                rotated.persisted().getCreatedAt());
    }
}
