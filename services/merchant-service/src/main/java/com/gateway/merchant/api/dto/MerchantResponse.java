package com.gateway.merchant.api.dto;

import com.gateway.merchant.domain.Branding;
import com.gateway.merchant.domain.MerchantMode;
import com.gateway.merchant.domain.MerchantStatus;
import java.time.Instant;

public record MerchantResponse(
        String id,
        String name,
        String callbackUrl,
        String returnUrlPattern,
        String cancelUrlPattern,
        Branding branding,
        MerchantMode mode,
        MerchantStatus status,
        Instant createdAt,
        Instant updatedAt) {}
