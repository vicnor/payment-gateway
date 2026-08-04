package com.gateway.payment.client;

/** Outcome returned by the acquirer (mirrors test-acquirer-service's {@code AcquirerOutcome}). */
public enum AcquirerOutcome {
    APPROVED,
    DECLINED,
    ERROR
}
