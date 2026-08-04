package com.gateway.payment.client;

/**
 * Result of a call to the acquirer's authorize endpoint.
 *
 * <p>{@code outcome} — APPROVED, DECLINED, or ERROR. {@code authCode} and {@code acquirerReference}
 * are present only on APPROVED. {@code errorCode} is present on DECLINED/ERROR.
 */
public record AcquireResult(
        AcquirerOutcome outcome, String authCode, String acquirerReference, String errorCode) {}
