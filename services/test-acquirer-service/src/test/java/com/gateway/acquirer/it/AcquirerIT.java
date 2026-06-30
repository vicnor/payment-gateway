package com.gateway.acquirer.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.acquirer.TestAcquirerServiceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(
        classes = TestAcquirerServiceApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        properties = {
            "gateway.acquirer.internal.callers[0].id=payment-service",
            "gateway.acquirer.internal.callers[0].secret=it-secret",
            "gateway.acquirer.timeout-seconds=0"
        })
class AcquirerIT {

    private static final String CALLER_ID = "payment-service";
    private static final String CALLER_SECRET = "it-secret";

    @Autowired TestRestTemplate restTemplate;

    // ------------------------------------------------------------------
    // Happy paths — one per card in the catalogue
    // ------------------------------------------------------------------

    @Test
    void visaApproveCardReturnsApprovedOutcome() {
        ResponseEntity<String> response = authorize("4242424242424242", "ref_it_visa_approve");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"outcome\":\"APPROVED\"");
        assertThat(response.getBody()).contains("\"auth_code\":");
        assertThat(response.getBody()).contains("\"acquirer_reference\":\"acq_test_");
    }

    @Test
    void mastercardApproveCardReturnsApprovedOutcome() {
        ResponseEntity<String> response = authorize("5555555555554444", "ref_it_mc_approve");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"outcome\":\"APPROVED\"");
    }

    @Test
    void declineCardReturnsDeclinedOutcome() {
        ResponseEntity<String> response = authorize("4000000000000002", "ref_it_decline");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"outcome\":\"DECLINED\"");
        assertThat(response.getBody()).contains("\"error_code\":\"card_declined\"");
    }

    @Test
    void insufficientFundsCardReturnsDeclinedWithInsufficientFundsCode() {
        ResponseEntity<String> response = authorize("4000000000009995", "ref_it_insuf");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"outcome\":\"DECLINED\"");
        assertThat(response.getBody()).contains("\"error_code\":\"insufficient_funds\"");
    }

    @Test
    void timeoutCardReturns504() {
        ResponseEntity<String> response = authorize("4000000000000341", "ref_it_timeout");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(response.getBody()).contains("\"outcome\":\"ERROR\"");
        assertThat(response.getBody()).contains("\"error_code\":\"acquirer_timeout\"");
    }

    @Test
    void processingErrorCardReturnsErrorOutcome() {
        ResponseEntity<String> response = authorize("4000000000000119", "ref_it_proc_err");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"outcome\":\"ERROR\"");
        assertThat(response.getBody()).contains("\"error_code\":\"processing_error\"");
    }

    @Test
    void unknownPanReturnsDeclined() {
        ResponseEntity<String> response = authorize("4111111111111111", "ref_it_unknown");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"outcome\":\"DECLINED\"");
        assertThat(response.getBody()).contains("\"error_code\":\"card_declined\"");
    }

    // ------------------------------------------------------------------
    // Auth
    // ------------------------------------------------------------------

    @Test
    void missingAuthHeadersReturnForbidden() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        "/internal/v1/authorize",
                        new HttpEntity<>(authorizeBody("4242424242424242"), headers),
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void wrongSecretReturnsForbidden() {
        ResponseEntity<String> response =
                authorizeWithSecret("4242424242424242", "wrong-secret", "ref_it_bad_secret");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------
    // Request log
    // ------------------------------------------------------------------

    @Test
    void requestLogContainsEntryAfterAuthorize() {
        String uniqueRef = "ref_it_log_" + System.nanoTime();
        authorize("4242424242424242", uniqueRef);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Caller-Service", CALLER_ID);
        headers.set("X-Internal-Token", CALLER_SECRET);
        ResponseEntity<String> logResponse =
                restTemplate.exchange(
                        "/internal/v1/requests",
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        String.class);

        assertThat(logResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(logResponse.getBody()).contains(uniqueRef);
        assertThat(logResponse.getBody()).contains("\"last4\":\"4242\"");
        assertThat(logResponse.getBody()).contains("\"outcome\":\"APPROVED\"");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ResponseEntity<String> authorize(String pan, String reference) {
        return authorizeWithSecret(pan, CALLER_SECRET, reference);
    }

    private ResponseEntity<String> authorizeWithSecret(
            String pan, String secret, String reference) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Caller-Service", CALLER_ID);
        headers.set("X-Internal-Token", secret);
        return restTemplate.postForEntity(
                "/internal/v1/authorize",
                new HttpEntity<>(authorizeBody(pan, reference), headers),
                String.class);
    }

    private static String authorizeBody(String pan) {
        return authorizeBody(pan, "ref_default");
    }

    private static String authorizeBody(String pan, String reference) {
        return """
               {
                 "pan": "%s",
                 "exp_month": 12,
                 "exp_year": 2027,
                 "cvv": "123",
                 "amount": 1000,
                 "currency": "DKK",
                 "reference": "%s"
               }
               """
                .formatted(pan, reference);
    }
}
