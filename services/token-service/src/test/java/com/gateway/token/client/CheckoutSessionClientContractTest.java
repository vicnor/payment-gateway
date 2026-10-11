package com.gateway.token.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class CheckoutSessionClientContractTest {

    @Test
    void readsCheckoutServicesSnakeCaseAuthenticationResponse() throws Exception {
        String response =
                """
                {
                  "session_id": "cs_test",
                  "merchant_id": "mer_test",
                  "expires_at": 1893456000
                }
                """;

        CheckoutSessionClient.AuthenticatedSession result =
                new ObjectMapper()
                        .readValue(
                                response, CheckoutSessionClient.AuthenticatedSession.class);

        assertThat(result.sessionId()).isEqualTo("cs_test");
        assertThat(result.merchantId()).isEqualTo("mer_test");
        assertThat(result.expiresAt()).isEqualTo(1893456000);
    }
}
