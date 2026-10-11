package com.gateway.token.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public interface CheckoutSessionClient {
    AuthenticatedSession authenticate(String sessionId, String secretDigest);

    record AuthenticatedSession(
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("merchant_id") String merchantId,
            @JsonProperty("expires_at") long expiresAt) {}
}
