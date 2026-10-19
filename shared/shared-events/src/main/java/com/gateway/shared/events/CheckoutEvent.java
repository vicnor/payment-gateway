package com.gateway.shared.events;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Immutable v1 checkout wire contract. New fields require a new event version. */
public record CheckoutEvent(
        String id, String object, EventType type, long created, boolean livemode, Data data) {
    public record Data(Session object) {}

    public record Session(
            String id,
            String object,
            @JsonProperty("merchant_id") String merchantId,
            String status,
            long amount,
            String currency,
            @JsonProperty("merchant_reference") String merchantReference,
            @JsonProperty("payment_id") String paymentId,
            long created,
            @JsonProperty("updated_at") long updatedAt,
            @JsonProperty("expires_at") long expiresAt,
            @JsonProperty("completed_at") Long completedAt) {}
}
