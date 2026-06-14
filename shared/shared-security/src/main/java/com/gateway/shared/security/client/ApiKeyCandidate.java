package com.gateway.shared.security.client;

import com.gateway.shared.security.KeyMode;
import java.util.UUID;

public record ApiKeyCandidate(
        UUID id, String merchantId, String keyPrefix, String keyHash, KeyMode mode) {}
