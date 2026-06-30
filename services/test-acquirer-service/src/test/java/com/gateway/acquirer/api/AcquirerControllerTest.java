package com.gateway.acquirer.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gateway.acquirer.api.dto.AuthorizeResponse;
import com.gateway.acquirer.api.dto.LoggedRequest;
import com.gateway.acquirer.config.AcquirerProperties;
import com.gateway.acquirer.config.AcquirerProperties.InternalProperties;
import com.gateway.acquirer.config.InternalCallerConfig;
import com.gateway.acquirer.domain.AcquirerOutcome;
import com.gateway.acquirer.domain.AcquirerService;
import com.gateway.acquirer.domain.AuthorizeResult;
import com.gateway.shared.security.CallerConfig;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AcquirerController.class)
@Import({AcquirerControllerTest.TestConfig.class, InternalCallerConfig.class})
class AcquirerControllerTest {

    private static final String CALLER_ID = "payment-service";
    private static final String SECRET = "test-secret";

    @TestConfiguration
    static class TestConfig {

        @Bean
        AcquirerProperties acquirerProperties() {
            return new AcquirerProperties(
                    new InternalProperties(List.of(new CallerConfig(CALLER_ID, SECRET))), 0L);
        }
    }

    @Autowired MockMvc mockMvc;

    @MockitoBean AcquirerService acquirerService;

    // ------------------------------------------------------------------
    // Auth
    // ------------------------------------------------------------------

    @Test
    void missingCallerServiceHeaderReturnsForbidden() throws Exception {
        mockMvc.perform(
                        post("/internal/v1/authorize")
                                .header("X-Internal-Token", SECRET)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(authorizeBody("4242424242424242")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.type").value("permission_error"));
    }

    @Test
    void missingInternalTokenHeaderReturnsForbidden() throws Exception {
        mockMvc.perform(
                        post("/internal/v1/authorize")
                                .header("X-Caller-Service", CALLER_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(authorizeBody("4242424242424242")))
                .andExpect(status().isForbidden());
    }

    @Test
    void wrongSecretReturnsForbidden() throws Exception {
        mockMvc.perform(
                        post("/internal/v1/authorize")
                                .header("X-Caller-Service", CALLER_ID)
                                .header("X-Internal-Token", "wrong")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(authorizeBody("4242424242424242")))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownCallerReturnsForbidden() throws Exception {
        mockMvc.perform(
                        post("/internal/v1/authorize")
                                .header("X-Caller-Service", "unknown-service")
                                .header("X-Internal-Token", SECRET)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(authorizeBody("4242424242424242")))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // Outcomes
    // ------------------------------------------------------------------

    @Test
    void approvedCardReturns200WithAuthCode() throws Exception {
        when(acquirerService.authorize(any()))
                .thenReturn(
                        AuthorizeResult.ok(
                                new AuthorizeResponse(
                                        AcquirerOutcome.APPROVED,
                                        "ABC12345",
                                        "acq_test_01ABC",
                                        null)));

        mockMvc.perform(
                        post("/internal/v1/authorize")
                                .headers(internalHeaders())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(authorizeBody("4242424242424242")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPROVED"))
                .andExpect(jsonPath("$.auth_code").value("ABC12345"))
                .andExpect(jsonPath("$.acquirer_reference").value("acq_test_01ABC"))
                .andExpect(jsonPath("$.error_code").doesNotExist());
    }

    @Test
    void declinedCardReturns200WithErrorCode() throws Exception {
        when(acquirerService.authorize(any()))
                .thenReturn(
                        AuthorizeResult.ok(
                                new AuthorizeResponse(
                                        AcquirerOutcome.DECLINED, null, null, "card_declined")));

        mockMvc.perform(
                        post("/internal/v1/authorize")
                                .headers(internalHeaders())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(authorizeBody("4000000000000002")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DECLINED"))
                .andExpect(jsonPath("$.error_code").value("card_declined"))
                .andExpect(jsonPath("$.auth_code").doesNotExist());
    }

    @Test
    void insufficientFundsReturns200WithErrorCode() throws Exception {
        when(acquirerService.authorize(any()))
                .thenReturn(
                        AuthorizeResult.ok(
                                new AuthorizeResponse(
                                        AcquirerOutcome.DECLINED,
                                        null,
                                        null,
                                        "insufficient_funds")));

        mockMvc.perform(
                        post("/internal/v1/authorize")
                                .headers(internalHeaders())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(authorizeBody("4000000000009995")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DECLINED"))
                .andExpect(jsonPath("$.error_code").value("insufficient_funds"));
    }

    @Test
    void processingErrorReturns200WithErrorCode() throws Exception {
        when(acquirerService.authorize(any()))
                .thenReturn(
                        AuthorizeResult.ok(
                                new AuthorizeResponse(
                                        AcquirerOutcome.ERROR, null, null, "processing_error")));

        mockMvc.perform(
                        post("/internal/v1/authorize")
                                .headers(internalHeaders())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(authorizeBody("4000000000000119")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ERROR"))
                .andExpect(jsonPath("$.error_code").value("processing_error"));
    }

    @Test
    void timeoutCardReturns504() throws Exception {
        when(acquirerService.authorize(any()))
                .thenReturn(
                        AuthorizeResult.gatewayTimeout(
                                new AuthorizeResponse(
                                        AcquirerOutcome.ERROR, null, null, "acquirer_timeout")));

        mockMvc.perform(
                        post("/internal/v1/authorize")
                                .headers(internalHeaders())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(authorizeBody("4000000000000341")))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.outcome").value("ERROR"))
                .andExpect(jsonPath("$.error_code").value("acquirer_timeout"));
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    @Test
    void missingPanReturns400() throws Exception {
        String body =
                """
                { "exp_month": 12, "exp_year": 2027, "cvv": "123",
                  "amount": 1000, "currency": "DKK", "reference": "ref1" }
                """;

        mockMvc.perform(
                        post("/internal/v1/authorize")
                                .headers(internalHeaders())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // Request log
    // ------------------------------------------------------------------

    @Test
    void requestLogEndpointReturnsSnapshot() throws Exception {
        when(acquirerService.getLog())
                .thenReturn(
                        List.of(
                                new LoggedRequest(
                                        Instant.parse("2026-06-30T10:00:00Z"),
                                        "4242",
                                        1000L,
                                        "DKK",
                                        "ref_001",
                                        AcquirerOutcome.APPROVED)));

        mockMvc.perform(get("/internal/v1/requests").headers(internalHeaders()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].last4").value("4242"))
                .andExpect(jsonPath("$[0].outcome").value("APPROVED"))
                .andExpect(jsonPath("$[0].amount").value(1000))
                .andExpect(jsonPath("$[0].currency").value("DKK"));
    }

    @Test
    void requestLogWithoutAuthReturnsForbidden() throws Exception {
        mockMvc.perform(get("/internal/v1/requests")).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static org.springframework.http.HttpHeaders internalHeaders() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("X-Caller-Service", CALLER_ID);
        headers.set("X-Internal-Token", SECRET);
        return headers;
    }

    private static String authorizeBody(String pan) {
        return """
               {
                 "pan": "%s",
                 "exp_month": 12,
                 "exp_year": 2027,
                 "cvv": "123",
                 "amount": 1000,
                 "currency": "DKK",
                 "reference": "ref_test_001"
               }
               """
                .formatted(pan);
    }
}
