package com.gateway.merchant.api.dto;

import com.gateway.merchant.domain.MerchantMode;
import java.time.Instant;
import java.util.UUID;

public record IssueApiKeyResponse(
        UUID id, String key, String keyPrefix, MerchantMode mode, Instant createdAt) {}
