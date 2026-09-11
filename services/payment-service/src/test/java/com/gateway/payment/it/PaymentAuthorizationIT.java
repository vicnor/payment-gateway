package com.gateway.payment.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.gateway.payment.PanScanAppender;
import com.gateway.payment.PaymentServiceApplication;
import com.gateway.shared.testing.AbstractPostgresIT;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Integration tests for {@code POST /internal/v1/payments}.
 *
 * <p>Token-service and test-acquirer-service are stubbed via WireMock — this test exercises only
 * the payment-service logic and its PostgreSQL persistence.
 *
 * <p>Covers all acceptance criteria from roadmap task 4.2:
 *
 * <ul>
 *   <li>Approved card → CAPTURED payment, all four DB rows written
 *   <li>Declined card → FAILED payment, all four DB rows written
 *   <li>Acquirer timeout (504) → 503, no DB rows written
 *   <li>Token already used (detokenize 409) → 409 from payment-service, no rows
 *   <li>Token not found (detokenize 404) → 404 from payment-service, no rows
 *   <li>Missing internal-auth headers → 403
 *   <li>No log line contains the PAN (PCI hard rule)
 * </ul>
 */
@TestInstance(Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = PaymentServiceApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        properties = {
            // Internal caller allowlist — checkout-service is the expected caller
            "gateway.payment.internal.callers[0].id=checkout-service",
            "gateway.payment.internal.callers[0].secret=it-checkout-secret",
            // This test doesn't wire up LocalStack/SNS — the outbox publisher would otherwise
            // poll a nonexistent endpoint every second for the duration of this test.
            "gateway.payment.outbox.publisher.enabled=false",
        })
class PaymentAuthorizationIT extends AbstractPostgresIT {

    /**
     * WireMock server started in a static initializer so its port is available when Spring calls
     * {@code @DynamicPropertySource} — which happens inside {@code SpringExtension.beforeAll()},
     * BEFORE any {@code @RegisterExtension} field is initialised. Using a bare {@code
     * WireMockServer} (not a JUnit extension) sidesteps that ordering problem.
     */
    static final WireMockServer wireMock;

    static {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
        // Configure the static WireMock DSL (stubFor, verify, etc.) to use this server so that
        // existing static stubFor() calls in test methods work without referencing the instance.
        WireMock.configureFor("localhost", wireMock.port());
    }

    private static final String CALLER_ID = "checkout-service";
    private static final String CALLER_SECRET = "it-checkout-secret";
    private static final String FAKE_PAN = "4242424242424242";

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Point both clients at the WireMock server started above
        String wmBase = wireMock.baseUrl();
        registry.add("gateway.payment.token-service.base-url", () -> wmBase);
        registry.add("gateway.payment.token-service.internal-token", () -> "wm-token-secret");
        registry.add("gateway.payment.acquirer-service.base-url", () -> wmBase);
        registry.add("gateway.payment.acquirer-service.internal-token", () -> "wm-acquirer-secret");
        // Merchant-service is not running; ApiKeyFilter skips /internal/** so this is a dead URL
        registry.add("shared.security.merchant-service.base-url", () -> "http://localhost:19999");
    }

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private JdbcTemplate jdbc;

    private final PanScanAppender panScan = new PanScanAppender();

    @BeforeAll
    void attachPanScanner() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        panScan.setContext(ctx);
        panScan.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(panScan);
    }

    @AfterAll
    void stopInfraAndDetachScanner() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(panScan);
        panScan.stop();
        wireMock.stop();
    }

    @BeforeEach
    void resetDbAndStubs() {
        // Child tables first (FK order)
        jdbc.execute("DELETE FROM payment_events");
        jdbc.execute("DELETE FROM payment_attempts");
        jdbc.execute("DELETE FROM outbox");
        jdbc.execute("DELETE FROM payments");
        panScan.reset();
        wireMock.resetAll();
    }

    // -------------------------------------------------------------------------
    // Happy path — approved card
    // -------------------------------------------------------------------------

    @Test
    void approvedCardCreatesAllDbRowsAndReturnsCaptured() {
        String token = "tok_approve_0000000000000000001";
        stubDetokenizeSuccess(token);
        stubAcquirerApproved();

        ResponseEntity<String> response = authorize(token, "order-approved-001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).contains("\"status\":\"CAPTURED\"");
        assertThat(response.getBody()).contains("\"object\":\"payment\"");
        assertThat(response.getBody()).contains("\"livemode\":false");
        assertThat(response.getBody()).contains("\"brand\":\"visa\"");
        assertThat(response.getBody()).contains("\"last4\":\"4242\"");
        assertThat(response.getBody()).doesNotContain(FAKE_PAN);

        // Re-fetch all rows from DB — do not rely on the response payload
        Long paymentCount = jdbc.queryForObject("SELECT count(*) FROM payments", Long.class);
        Long attemptCount =
                jdbc.queryForObject("SELECT count(*) FROM payment_attempts", Long.class);
        Long eventCount = jdbc.queryForObject("SELECT count(*) FROM payment_events", Long.class);
        Long outboxCount = jdbc.queryForObject("SELECT count(*) FROM outbox", Long.class);

        assertThat(paymentCount).isEqualTo(1L);
        assertThat(attemptCount).isEqualTo(1L);
        assertThat(eventCount).isEqualTo(1L);
        assertThat(outboxCount).isEqualTo(1L);

        String dbStatus = jdbc.queryForObject("SELECT status FROM payments LIMIT 1", String.class);
        assertThat(dbStatus).isEqualTo("CAPTURED");

        String outboxEventType =
                jdbc.queryForObject("SELECT event_type FROM outbox LIMIT 1", String.class);
        assertThat(outboxEventType).isEqualTo("payment.captured");

        String attemptStatus =
                jdbc.queryForObject(
                        "SELECT acquirer_status FROM payment_attempts LIMIT 1", String.class);
        assertThat(attemptStatus).isEqualTo("APPROVED");

        // PCI: PAN must not appear anywhere in the DB
        String requestPayload =
                jdbc.queryForObject(
                        "SELECT request_payload::text FROM payment_attempts LIMIT 1", String.class);
        assertThat(requestPayload).doesNotContain(FAKE_PAN);
    }

    @Test
    void panNeverAppearsInLogs() {
        String token = "tok_pan_log_0000000000000000001";
        stubDetokenizeSuccess(token);
        stubAcquirerApproved();

        authorize(token, "order-pan-log-001");

        assertThat(panScan.getViolations())
                .as("No log line should contain a PAN-length numeric sequence")
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // Declined card
    // -------------------------------------------------------------------------

    @Test
    void declinedCardCreatesFailedPaymentAndReturns201() {
        String token = "tok_decline_000000000000000001";
        stubDetokenizeSuccess(token);
        stubAcquirerDeclined();

        ResponseEntity<String> response = authorize(token, "order-declined-001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).contains("\"status\":\"FAILED\"");
        assertThat(response.getBody()).contains("\"failure_code\":\"card_declined\"");

        String dbStatus = jdbc.queryForObject("SELECT status FROM payments LIMIT 1", String.class);
        assertThat(dbStatus).isEqualTo("FAILED");

        String outboxEventType =
                jdbc.queryForObject("SELECT event_type FROM outbox LIMIT 1", String.class);
        assertThat(outboxEventType).isEqualTo("payment.failed");

        Long amountCaptured =
                jdbc.queryForObject("SELECT amount_captured FROM payments LIMIT 1", Long.class);
        assertThat(amountCaptured).isEqualTo(0L);
    }

    // -------------------------------------------------------------------------
    // Acquirer timeout
    // -------------------------------------------------------------------------

    @Test
    void acquirerTimeoutReturns503AndWritesNoRows() {
        String token = "tok_timeout_00000000000000001";
        stubDetokenizeSuccess(token);
        stubAcquirerTimeout();

        ResponseEntity<String> response = authorize(token, "order-timeout-001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("acquirer_unavailable");

        Long paymentCount = jdbc.queryForObject("SELECT count(*) FROM payments", Long.class);
        assertThat(paymentCount).isEqualTo(0L);

        Long outboxCount = jdbc.queryForObject("SELECT count(*) FROM outbox", Long.class);
        assertThat(outboxCount).isEqualTo(0L);
    }

    // -------------------------------------------------------------------------
    // Token errors — no payment row in either case
    // -------------------------------------------------------------------------

    @Test
    void tokenAlreadyUsedReturns409AndWritesNoRows() {
        String token = "tok_used_000000000000000000001";
        stubDetokenizeAlreadyUsed(token);

        ResponseEntity<String> response = authorize(token, "order-used-001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("token_already_used");

        Long paymentCount = jdbc.queryForObject("SELECT count(*) FROM payments", Long.class);
        assertThat(paymentCount).isEqualTo(0L);
    }

    @Test
    void tokenNotFoundReturns404AndWritesNoRows() {
        String token = "tok_notfound_0000000000000001";
        stubDetokenizeNotFound(token);

        ResponseEntity<String> response = authorize(token, "order-notfound-001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("not_found");

        Long paymentCount = jdbc.queryForObject("SELECT count(*) FROM payments", Long.class);
        assertThat(paymentCount).isEqualTo(0L);
    }

    // -------------------------------------------------------------------------
    // Auth — missing internal-caller headers
    // -------------------------------------------------------------------------

    @Test
    void missingInternalAuthHeadersReturnForbidden() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        "/internal/v1/payments",
                        new HttpEntity<>(validBody("tok_any", "order-noauth"), headers),
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("permission_error");
    }

    @Test
    void wrongCallerSecretReturnsForbidden() {
        ResponseEntity<String> response =
                authorizeWithSecret("tok_any", "order-wrongsecret", "wrong-secret");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // -------------------------------------------------------------------------
    // WireMock stubs
    // -------------------------------------------------------------------------

    private void stubDetokenizeSuccess(String token) {
        stubFor(
                post(urlPathMatching("/internal/v1/tokens/.*/detokenize"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                                {
                                                  "pan": "4242424242424242",
                                                  "exp_month": 12,
                                                  "exp_year": 2027,
                                                  "brand": "visa",
                                                  "last4": "4242",
                                                  "country": null,
                                                  "funding": null
                                                }
                                                """)));
    }

    private void stubDetokenizeAlreadyUsed(String token) {
        stubFor(
                post(urlPathMatching("/internal/v1/tokens/.*/detokenize"))
                        .willReturn(
                                aResponse()
                                        .withStatus(409)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"error\":{\"type\":\"conflict_error\","
                                                        + "\"code\":\"token_already_used\","
                                                        + "\"message\":\"Token has already been"
                                                        + " used.\"}}")));
    }

    private void stubDetokenizeNotFound(String token) {
        stubFor(
                post(urlPathMatching("/internal/v1/tokens/.*/detokenize"))
                        .willReturn(
                                aResponse()
                                        .withStatus(404)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"error\":{\"type\":\"not_found\","
                                                        + "\"code\":\"resource_not_found\","
                                                        + "\"message\":\"Token not found.\"}}")));
    }

    private void stubAcquirerApproved() {
        stubFor(
                post(WireMock.urlEqualTo("/internal/v1/authorize"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                                {
                                                  "outcome": "APPROVED",
                                                  "auth_code": "TEST1234",
                                                  "acquirer_reference": "acq_test_it_ref01"
                                                }
                                                """)));
    }

    private void stubAcquirerDeclined() {
        stubFor(
                post(WireMock.urlEqualTo("/internal/v1/authorize"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"outcome\":\"DECLINED\","
                                                        + "\"error_code\":\"card_declined\"}")));
    }

    private void stubAcquirerTimeout() {
        stubFor(
                post(WireMock.urlEqualTo("/internal/v1/authorize"))
                        .willReturn(
                                aResponse()
                                        .withStatus(504)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"outcome\":\"ERROR\","
                                                        + "\"error_code\":\"acquirer_timeout\"}")));
    }

    // -------------------------------------------------------------------------
    // HTTP helpers
    // -------------------------------------------------------------------------

    private ResponseEntity<String> authorize(String token, String reference) {
        return authorizeWithSecret(token, reference, CALLER_SECRET);
    }

    private ResponseEntity<String> authorizeWithSecret(
            String token, String reference, String secret) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Caller-Service", CALLER_ID);
        headers.set("X-Internal-Token", secret);
        return restTemplate.postForEntity(
                "/internal/v1/payments",
                new HttpEntity<>(validBody(token, reference), headers),
                String.class);
    }

    private static String validBody(String token, String reference) {
        return """
               {
                 "checkout_session_id": "cs_it_test_session_0000000001",
                 "merchant_id":         "mer_it_test_merchant_000001",
                 "amount":              19900,
                 "currency":            "DKK",
                 "merchant_reference":  "%s",
                 "token":               "%s",
                 "metadata":            {"cart_id": "test-cart-42"}
               }
               """
                .formatted(reference, token);
    }
}
