package com.gateway.shared.security.client;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.gateway.shared.security.KeyMode;
import java.util.UUID;

public record ApiKeyCandidate(
        UUID id,
        @JsonAlias("merchant_id") String merchantId,
        @JsonAlias("key_prefix") String keyPrefix,
        @JsonAlias("key_hash") String keyHash,
        KeyMode mode) {}
