package com.gateway.merchant.api.dto.internal;

import com.gateway.merchant.domain.MerchantMode;
import java.util.UUID;

public record ApiKeyCandidate(UUID id, String merchantId, String keyPrefix, String keyHash, MerchantMode mode) {}
