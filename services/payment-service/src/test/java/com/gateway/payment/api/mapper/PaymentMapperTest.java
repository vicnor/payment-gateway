package com.gateway.payment.api.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.payment.api.dto.PaymentResponse;
import com.gateway.payment.domain.Payment;
import com.gateway.payment.domain.PaymentMethodDetails;
import com.gateway.payment.domain.PaymentStatus;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentMapperTest {

    @Test
    void capturedPaymentMapsToCorrectShape() {
        Payment payment = capturedPayment();

        PaymentResponse response = PaymentMapper.toResponse(payment);

        assertThat(response.id()).startsWith("pay_");
        assertThat(response.object()).isEqualTo("payment");
        assertThat(response.checkoutSessionId()).isEqualTo("cs_test_session");
        assertThat(response.amount()).isEqualTo(19900L);
        assertThat(response.amountCaptured()).isEqualTo(19900L);
        assertThat(response.amountRefunded()).isEqualTo(0L);
        assertThat(response.currency()).isEqualTo("DKK");
        assertThat(response.status()).isEqualTo("CAPTURED");
        assertThat(response.paymentMethod()).isEqualTo("card");
        assertThat(response.merchantReference()).isEqualTo("order-001");
        assertThat(response.failureCode()).isNull();
        assertThat(response.failureMessage()).isNull();
        assertThat(response.livemode()).isFalse();
    }

    @Test
    void createdTimestampIsEpochSeconds() {
        Payment payment = capturedPayment();

        PaymentResponse response = PaymentMapper.toResponse(payment);

        // created is epoch seconds (not millis) — a value in the current decade is > 1e9
        assertThat(response.created()).isGreaterThan(1_700_000_000L);
        assertThat(response.created()).isLessThan(2_000_000_000L);
    }

    @Test
    void capturedTimestampsArePopulated() {
        Payment payment = capturedPayment();

        PaymentResponse response = PaymentMapper.toResponse(payment);

        assertThat(response.authorizedAt()).isNotNull().isGreaterThan(0L);
        assertThat(response.capturedAt()).isNotNull().isGreaterThan(0L);
    }

    @Test
    void failedPaymentHasNullTimestampsAndFailureFields() {
        Payment payment = failedPayment();

        PaymentResponse response = PaymentMapper.toResponse(payment);

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.amountCaptured()).isEqualTo(0L);
        assertThat(response.authorizedAt()).isNull();
        assertThat(response.capturedAt()).isNull();
        assertThat(response.failureCode()).isEqualTo("card_declined");
        assertThat(response.failureMessage()).isEqualTo("Your card was declined.");
    }

    @Test
    void paymentMethodDetailsAreMapped() {
        Payment payment = capturedPayment();

        PaymentResponse response = PaymentMapper.toResponse(payment);

        assertThat(response.paymentMethodDetails()).isNotNull();
        assertThat(response.paymentMethodDetails().brand()).isEqualTo("visa");
        assertThat(response.paymentMethodDetails().last4()).isEqualTo("4242");
        assertThat(response.paymentMethodDetails().expMonth()).isEqualTo(12);
        assertThat(response.paymentMethodDetails().expYear()).isEqualTo(2027);
        assertThat(response.paymentMethodDetails().country()).isNull();
    }

    @Test
    void metadataIsPreserved() {
        Payment payment = capturedPayment();

        PaymentResponse response = PaymentMapper.toResponse(payment);

        assertThat(response.metadata()).containsEntry("cart_id", "abc123");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Payment capturedPayment() {
        PaymentMethodDetails pmd = new PaymentMethodDetails("visa", "4242", 12, 2027, null);
        Payment p =
                new Payment(
                        UUID.randomUUID(),
                        "pay_testulid000000000000000001",
                        "cs_test_session",
                        "mer_test_merchant",
                        "order-001",
                        19900L,
                        "DKK",
                        PaymentStatus.CAPTURED,
                        pmd,
                        Map.of("cart_id", "abc123"));
        p.setAmountCaptured(19900L);
        p.setAcquirer("test-acquirer");
        p.setAcquirerReference("acq_test_ref");
        p.setAuthCode("AUTH01");
        p.setAuthorizedAt(java.time.Instant.now());
        p.setCapturedAt(java.time.Instant.now());
        return p;
    }

    private static Payment failedPayment() {
        PaymentMethodDetails pmd = new PaymentMethodDetails("visa", "0002", 12, 2027, null);
        Payment p =
                new Payment(
                        UUID.randomUUID(),
                        "pay_testulid000000000000000002",
                        "cs_test_session",
                        "mer_test_merchant",
                        "order-002",
                        19900L,
                        "DKK",
                        PaymentStatus.FAILED,
                        pmd,
                        Map.of());
        p.setFailureCode("card_declined");
        p.setFailureMessage("Your card was declined.");
        return p;
    }
}
