package com.gateway.shared.web.idempotency;

import java.util.Map;

public interface IdempotencyStore {

    ClaimResult claim(Claim claim);

    void complete(IdempotencyContext context, CachedResponse response);

    void release(IdempotencyContext context);

    record Claim(
            String storageKey,
            String requestHash,
            String ownerToken,
            long nowEpochSecond,
            long leaseUntilEpochSecond,
            long expiresAtEpochSecond) {}

    sealed interface ClaimResult {
        record Acquired() implements ClaimResult {}

        record Replay(CachedResponse response) implements ClaimResult {}

        record Conflict() implements ClaimResult {}

        record InProgress() implements ClaimResult {}
    }

    record CachedResponse(
            int status, String contentType, Map<String, String> headers, byte[] body) {
        public CachedResponse {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }
}
