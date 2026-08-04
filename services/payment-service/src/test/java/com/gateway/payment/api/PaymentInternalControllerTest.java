package com.gateway.payment.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gateway.payment.api.dto.PaymentResponse;
import com.gateway.payment.api.dto.PaymentResponse.PaymentMethodDetailsResponse;
import com.gateway.payment.config.InternalCallerConfig;
import com.gateway.payment.config.PaymentProperties;
import com.gateway.payment.config.PaymentProperties.ClientProperties;
import com.gateway.payment.config.PaymentProperties.InternalProperties;
import com.gateway.payment.domain.PaymentAuthorizationService;
import com.gateway.shared.security.CallerConfig;
import com.gateway.shared.web.error.AcquirerUnavailableException;
import com.gateway.shared.web.error.ConflictException;
import com.gateway.shared.web.error.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PaymentInternalController.class)
@Import({PaymentInternalControllerTest.TestConfig.class, InternalCallerConfig.class})
class PaymentInternalControllerTest {

    private static final String CALLER_ID = "checkout-service";
    private static final String SECRET = "test-secret";

    @TestConfiguration
    static class TestConfig {

        @Bean
        PaymentProperties paymentProperties() {
            return new PaymentProperties(
                    new ClientProperties("http://localhost:19103", "smoke-token"),
                    new ClientProperties("http://localhost:19105", "smoke-acq"),
                    new InternalProperties(List.of(new CallerConfig(CALLER_ID, SECRET))));
        }
    }

    @Autowired MockMvc mockMvc;

    @MockitoBean PaymentAuthorizationService authorizationService;

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    void validRequestReturns201WithPaymentShape() throws Exception {
        long now = Instant.now().getEpochSecond();
        when(authorizationService.charge(any()))
                .thenReturn(
                        new PaymentResponse(
                                "pay_01ABC",
                                "payment",
                                "cs_01ABC",
                                19900L,
                                19900L,
                                0L,
                                "DKK",
                                "CAPTURED",
                                "card",
                                new PaymentMethodDetailsResponse("visa", "4242", 12, 2027, null),
                                "order-001",
                                null,
                                null,
                                now,
                                now,
                                now,
                                Map.of(),
                                false));

        mockMvc.perform(
                        post("/internal/v1/payments")
                                .header("X-Caller-Service", CALLER_ID)
                                .header("X-Internal-Token", SECRET)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.object").value("payment"))
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.livemode").value(false))
                .andExpect(jsonPath("$.payment_method_details.brand").value("visa"))
                .andExpect(jsonPath("$.payment_method_details.last4").value("4242"));
    }

    // -------------------------------------------------------------------------
    // Bean validation — missing / invalid fields → 400
    // -------------------------------------------------------------------------

    @Test
    void missingTokenReturns400() throws Exception {
        mockMvc.perform(
                        post("/internal/v1/payments")
                                .header("X-Caller-Service", CALLER_ID)
                                .header("X-Internal-Token", SECRET)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "checkout_session_id": "cs_01",
                                          "merchant_id": "mer_01",
                                          "amount": 100,
                                          "currency": "DKK",
                                          "merchant_reference": "ref-001"
                                        }
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_request"));
    }

    @Test
    void blankAmountReturns400() throws Exception {
        mockMvc.perform(
                        post("/internal/v1/payments")
                                .header("X-Caller-Service", CALLER_ID)
                                .header("X-Internal-Token", SECRET)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "checkout_session_id": "cs_01",
                                          "merchant_id": "mer_01",
                                          "currency": "DKK",
                                          "merchant_reference": "ref-001",
                                          "token": "tok_01"
                                        }
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_request"));
    }

    @Test
    void zeroAmountReturns400() throws Exception {
        mockMvc.perform(
                        post("/internal/v1/payments")
                                .header("X-Caller-Service", CALLER_ID)
                                .header("X-Internal-Token", SECRET)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "checkout_session_id": "cs_01",
                                          "merchant_id": "mer_01",
                                          "amount": 0,
                                          "currency": "DKK",
                                          "merchant_reference": "ref-001",
                                          "token": "tok_01"
                                        }
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_request"));
    }

    @Test
    void invalidCurrencyReturns400() throws Exception {
        mockMvc.perform(
                        post("/internal/v1/payments")
                                .header("X-Caller-Service", CALLER_ID)
                                .header("X-Internal-Token", SECRET)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "checkout_session_id": "cs_01",
                                          "merchant_id": "mer_01",
                                          "amount": 100,
                                          "currency": "dkk",
                                          "merchant_reference": "ref-001",
                                          "token": "tok_01"
                                        }
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_request"));
    }

    // -------------------------------------------------------------------------
    // Authentication — internal caller headers
    // -------------------------------------------------------------------------

    @Test
    void missingCallerServiceHeaderReturnsForbidden() throws Exception {
        mockMvc.perform(
                        post("/internal/v1/payments")
                                .header("X-Internal-Token", SECRET)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.type").value("permission_error"));
    }

    @Test
    void missingInternalTokenHeaderReturnsForbidden() throws Exception {
        mockMvc.perform(
                        post("/internal/v1/payments")
                                .header("X-Caller-Service", CALLER_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.type").value("permission_error"));
    }

    @Test
    void wrongSecretReturnsForbidden() throws Exception {
        mockMvc.perform(
                        post("/internal/v1/payments")
                                .header("X-Caller-Service", CALLER_ID)
                                .header("X-Internal-Token", "wrong-secret")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownCallerReturnsForbidden() throws Exception {
        mockMvc.perform(
                        post("/internal/v1/payments")
                                .header("X-Caller-Service", "unknown-service")
                                .header("X-Internal-Token", SECRET)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validBody()))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // Service exceptions propagated to the correct HTTP status
    // -------------------------------------------------------------------------

    @Test
    void tokenNotFoundReturns404() throws Exception {
        when(authorizationService.charge(any()))
                .thenThrow(new NotFoundException("Token", "tok_01"));

        mockMvc.perform(
                        post("/internal/v1/payments")
                                .header("X-Caller-Service", CALLER_ID)
                                .header("X-Internal-Token", SECRET)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("not_found"));
    }

    @Test
    void tokenAlreadyUsedReturns409() throws Exception {
        when(authorizationService.charge(any()))
                .thenThrow(new ConflictException("token_already_used", "Token already used."));

        mockMvc.perform(
                        post("/internal/v1/payments")
                                .header("X-Caller-Service", CALLER_ID)
                                .header("X-Internal-Token", SECRET)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("token_already_used"));
    }

    @Test
    void acquirerUnavailableReturns503() throws Exception {
        when(authorizationService.charge(any()))
                .thenThrow(new AcquirerUnavailableException("acquirer timed out"));

        mockMvc.perform(
                        post("/internal/v1/payments")
                                .header("X-Caller-Service", CALLER_ID)
                                .header("X-Internal-Token", SECRET)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validBody()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.type").value("acquirer_unavailable"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String validBody() {
        return """
               {
                 "checkout_session_id": "cs_01ABCDEFGHIJKLMNOPQRSTUVWX",
                 "merchant_id":         "mer_01ABCDEFGHIJKLMNOPQRSTUVWX",
                 "amount":              19900,
                 "currency":            "DKK",
                 "merchant_reference":  "order-001",
                 "token":               "tok_01ABCDEFGHIJKLMNOPQRSTUVWX"
               }
               """;
    }
}
