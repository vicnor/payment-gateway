package com.gateway.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.gateway.payment.api.dto.PaymentListResponse;
import com.gateway.payment.persistence.PaymentQueryRepository;
import com.gateway.payment.persistence.PaymentRepository;
import com.gateway.shared.web.error.NotFoundException;
import com.gateway.shared.web.error.ValidationException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentQueryServiceTest {

    private static final String MERCHANT_ID = "mer_test_merchant";
    private static final String PAYMENT_ID = "pay_01ARZ3NDEKTSV4RRFFQ69G5FAV";

    @Mock PaymentRepository paymentRepository;
    @Mock PaymentQueryRepository paymentQueryRepository;

    private PaymentQueryService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new PaymentQueryService(paymentRepository, paymentQueryRepository);
    }

    @Test
    void retrieveIsMerchantScoped() {
        Payment payment = payment(PAYMENT_ID, Instant.ofEpochSecond(200));
        when(paymentRepository.findByExternalIdAndMerchantId(PAYMENT_ID, MERCHANT_ID))
                .thenReturn(Optional.of(payment));

        assertThat(service.retrieve(MERCHANT_ID, PAYMENT_ID).id()).isEqualTo(PAYMENT_ID);
    }

    @Test
    void retrieveReturns404ForMissingOrForeignPayment() {
        when(paymentRepository.findByExternalIdAndMerchantId(PAYMENT_ID, MERCHANT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.retrieve(MERCHANT_ID, PAYMENT_ID))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void listFetchesOneExtraRowAndCalculatesHasMore() {
        List<Payment> rows =
                List.of(
                        payment("pay_01ARZ3NDEKTSV4RRFFQ69G5FAV", Instant.ofEpochSecond(300)),
                        payment("pay_01ARZ3NDEKTSV4RRFFQ69G5FAW", Instant.ofEpochSecond(200)),
                        payment("pay_01ARZ3NDEKTSV4RRFFQ69G5FAX", Instant.ofEpochSecond(100)));
        when(paymentQueryRepository.findMerchantPage(
                        eq(MERCHANT_ID), eq(null), eq(null), eq(null), eq(null), eq(3)))
                .thenReturn(rows);

        PaymentListResponse response = service.list(MERCHANT_ID, "2", null, null, null);

        assertThat(response.data())
                .extracting(r -> r.id())
                .containsExactly(rows.get(0).getExternalId(), rows.get(1).getExternalId());
        assertThat(response.hasMore()).isTrue();
        assertThat(response.object()).isEqualTo("list");
        assertThat(response.url()).isEqualTo("/v1/payments");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "0", "-1", "101"})
    void invalidLimitReturns400ValidationError(String limit) {
        assertThatThrownBy(() -> service.list(MERCHANT_ID, limit, null, null, null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("limit");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "-1"})
    void invalidCreatedFilterReturnsValidationError(String created) {
        assertThatThrownBy(() -> service.list(MERCHANT_ID, null, null, created, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void invertedCreatedRangeReturnsValidationError() {
        assertThatThrownBy(() -> service.list(MERCHANT_ID, null, null, "200", "100"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("created.gte");
    }

    @Test
    void malformedUnknownOrForeignCursorReturnsSameValidationError() {
        assertThatThrownBy(() -> service.list(MERCHANT_ID, null, "not-a-payment", null, null))
                .isInstanceOf(ValidationException.class)
                .hasMessage("starting_after is not a valid cursor.");

        when(paymentRepository.findByExternalIdAndMerchantId(PAYMENT_ID, MERCHANT_ID))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.list(MERCHANT_ID, null, PAYMENT_ID, null, null))
                .isInstanceOf(ValidationException.class)
                .hasMessage("starting_after is not a valid cursor.");
    }

    @Test
    void cursorMustBeInsideActiveCreatedRange() {
        Payment cursor = payment(PAYMENT_ID, Instant.ofEpochSecond(99));
        when(paymentRepository.findByExternalIdAndMerchantId(PAYMENT_ID, MERCHANT_ID))
                .thenReturn(Optional.of(cursor));

        assertThatThrownBy(() -> service.list(MERCHANT_ID, null, PAYMENT_ID, "100", "200"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("starting_after is not a valid cursor.");
    }

    private static Payment payment(String id, Instant createdAt) {
        Payment payment =
                new Payment(
                        UUID.randomUUID(),
                        id,
                        "cs_test_session",
                        MERCHANT_ID,
                        "order-001",
                        19900L,
                        "DKK",
                        PaymentStatus.CAPTURED,
                        new PaymentMethodDetails("visa", "4242", 12, 2027, "DK"),
                        Map.of());
        payment.setAmountCaptured(19900L);
        ReflectionTestUtils.setField(payment, "createdAt", createdAt);
        return payment;
    }
}
