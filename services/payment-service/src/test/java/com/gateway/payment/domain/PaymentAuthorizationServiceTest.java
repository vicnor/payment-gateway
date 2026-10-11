package com.gateway.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gateway.payment.api.dto.CreatePaymentRequest;
import com.gateway.payment.api.dto.PaymentResponse;
import com.gateway.payment.client.AcquirerClient;
import com.gateway.payment.client.TokenServiceClient;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentAuthorizationServiceTest {
    @Test
    void replayReturnsExistingPaymentWithoutDetokenizing() {
        TokenServiceClient tokens = mock(TokenServiceClient.class);
        AcquirerClient acquirer = mock(AcquirerClient.class);
        PaymentWriter writer = mock(PaymentWriter.class);
        Payment existing =
                new Payment(
                        UUID.randomUUID(),
                        "pay_01",
                        "ca_test",
                        "cs_01",
                        "mer_01",
                        "order-1",
                        19900L,
                        "DKK",
                        PaymentStatus.FAILED,
                        new PaymentMethodDetails("visa", "4242", 12, 2027, null),
                        Map.of());
        when(writer.findByCompletionAttemptId("ca_test")).thenReturn(existing);

        PaymentResponse response =
                new PaymentAuthorizationService(tokens, acquirer, writer)
                        .charge(
                                new CreatePaymentRequest(
                                        "ca_test", "cs_01", "mer_01", 19900L, "DKK", "order-1",
                                        "tok_01", Map.of()));

        assertThat(response.id()).isEqualTo("pay_01");
        verify(tokens, never()).detokenize("tok_01", "cs_01", "mer_01");
    }
}
