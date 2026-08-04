package com.gateway.payment.api.mapper;

import com.gateway.payment.api.dto.PaymentResponse;
import com.gateway.payment.api.dto.PaymentResponse.PaymentMethodDetailsResponse;
import com.gateway.payment.domain.Payment;
import com.gateway.payment.domain.PaymentMethodDetails;

/**
 * Stateless mapper from {@link Payment} entity to {@link PaymentResponse} DTO.
 *
 * <p>Timestamps are converted from {@link java.time.Instant} to Unix epoch seconds (long) as
 * required by {@code docs/architecture/api.md}. {@code livemode} is {@code false} for all v1
 * payments (only {@code test-acquirer-service} is wired — ADR-0001).
 */
public final class PaymentMapper {

    private PaymentMapper() {}

    public static PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(
                payment.getExternalId(),
                "payment",
                payment.getCheckoutSessionId(),
                payment.getAmount(),
                payment.getAmountCaptured(),
                payment.getAmountRefunded(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getPaymentMethod(),
                toMethodDetailsResponse(payment.getPaymentMethodDetails()),
                payment.getMerchantReference(),
                payment.getFailureCode(),
                payment.getFailureMessage(),
                payment.getCreatedAt().getEpochSecond(),
                payment.getAuthorizedAt() != null
                        ? payment.getAuthorizedAt().getEpochSecond()
                        : null,
                payment.getCapturedAt() != null ? payment.getCapturedAt().getEpochSecond() : null,
                payment.getMetadata(),
                false);
    }

    private static PaymentMethodDetailsResponse toMethodDetailsResponse(PaymentMethodDetails d) {
        if (d == null) return null;
        return new PaymentMethodDetailsResponse(
                d.brand(), d.last4(), d.expMonth(), d.expYear(), d.country());
    }
}
