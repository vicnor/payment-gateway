package com.gateway.payment.domain;

/**
 * Payment state machine.
 *
 * <p>v1 path (bold): {@code PENDING → CAPTURED | FAILED}.
 *
 * <p>{@code AUTHORIZED} is reserved for when split auth/capture is added (see
 * docs/architecture/data-model.md). {@code REQUIRES_CHALLENGE} is reserved for 3DS (ADR-0001).
 */
public enum PaymentStatus {
    PENDING,
    AUTHORIZED,
    CAPTURED,
    FAILED,
    CANCELLED,
    REFUNDED,
    PARTIALLY_REFUNDED,
    REQUIRES_CHALLENGE
}
