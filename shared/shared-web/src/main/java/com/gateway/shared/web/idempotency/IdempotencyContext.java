package com.gateway.shared.web.idempotency;

import java.util.concurrent.atomic.AtomicBoolean;

public final class IdempotencyContext {

    public static final String REQUEST_ATTRIBUTE = "gateway.idempotency.context";

    private final String storageKey;
    private final String requestHash;
    private final String ownerToken;
    private final AtomicBoolean completed = new AtomicBoolean();

    public IdempotencyContext(String storageKey, String requestHash, String ownerToken) {
        this.storageKey = storageKey;
        this.requestHash = requestHash;
        this.ownerToken = ownerToken;
    }

    public String storageKey() {
        return storageKey;
    }

    public String requestHash() {
        return requestHash;
    }

    public String ownerToken() {
        return ownerToken;
    }

    public boolean isCompleted() {
        return completed.get();
    }

    public void markCompleted() {
        completed.set(true);
    }
}
