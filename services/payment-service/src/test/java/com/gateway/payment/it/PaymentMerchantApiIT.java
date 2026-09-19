package com.gateway.payment.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.payment.PaymentServiceApplication;
import com.gateway.payment.domain.Payment;
import com.gateway.payment.domain.PaymentMethodDetails;
import com.gateway.payment.domain.PaymentStatus;
import com.gateway.payment.persistence.PaymentRepository;
import com.gateway.shared.security.ApiKeyFormat;
import com.gateway.shared.testing.AbstractPostgresIT;
import com.gateway.shared.testing.MerchantApiContract;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/** Full-stack coverage for the payment Merchant API, including API-key authentication. */
@TestInstance(Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = PaymentServiceApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "gateway.payment.outbox.publisher.enabled=false")
class PaymentMerchantApiIT extends AbstractPostgresIT {

    private static final String MERCHANT_ID = "mer_test_merchant";
    private static final String OTHER_MERCHANT_ID = "mer_other_merchant";
    private static final String API_KEY = "sk_test_01ARZ3NDEKTSV4RRFFQ69G5FAV_" + "a".repeat(43);
    private static final String API_KEY_PREFIX = ApiKeyFormat.extractPrefix(API_KEY);
    private static final UUID API_KEY_ID = UUID.fromString("018f4d8d-86a0-7000-8000-000000000001");
    private static final String API_KEY_HASH =
            Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(API_KEY);

    private static final WireMockServer wireMock;

    static {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("shared.security.merchant-service.base-url", wireMock::baseUrl);
        registry.add("gateway.payment.token-service.base-url", wireMock::baseUrl);
        registry.add("gateway.payment.token-service.internal-token", () -> "test-token-secret");
        registry.add("gateway.payment.acquirer-service.base-url", wireMock::baseUrl);
        registry.add(
                "gateway.payment.acquirer-service.internal-token", () -> "test-acquirer-secret");
    }

    @Autowired TestRestTemplate restTemplate;
    @Autowired PaymentRepository paymentRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper objectMapper;

    @BeforeAll
    void stubMerchantService() {
        stubFor(
                get(urlEqualTo("/internal/v1/api-keys/" + API_KEY_PREFIX))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                                {
                                                  "keys": [{
                                                    "id": "%s",
                                                    "merchant_id": "%s",
                                                    "key_prefix": "%s",
                                                    "key_hash": "%s",
                                                    "mode": "TEST"
                                                  }]
                                                }
                                                """
                                                        .formatted(
                                                                API_KEY_ID,
                                                                MERCHANT_ID,
                                                                API_KEY_PREFIX,
                                                                API_KEY_HASH))));
    }

    @AfterAll
    void stopWireMock() {
        wireMock.stop();
    }

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("DELETE FROM payment_events");
        jdbc.execute("DELETE FROM payment_attempts");
        jdbc.execute("DELETE FROM outbox");
        jdbc.execute("DELETE FROM payments");
    }

    @Test
    void retrievesOwnedPaymentWithDocumentedShape() throws Exception {
        String id = "pay_01ARZ3NDEKTSV4RRFFQ69G5FAV";
        savePayment(id, MERCHANT_ID, Instant.ofEpochSecond(200));

        ResponseEntity<String> response = getWithApiKey("/v1/payments/" + id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-Request-Id")).startsWith("req_");
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("id").asText()).isEqualTo(id);
        assertThat(body.path("object").asText()).isEqualTo("payment");
        assertThat(body.path("amount").asLong()).isEqualTo(19900L);
        assertThat(body.path("payment_method_details").path("last4").asText()).isEqualTo("4242");
        assertThat(body.path("livemode").asBoolean()).isFalse();
    }

    @Test
    void missingMalformedAndForeignPaymentsAllReturn404() {
        String foreignId = "pay_01ARZ3NDEKTSV4RRFFQ69G5FAW";
        savePayment(foreignId, OTHER_MERCHANT_ID, Instant.ofEpochSecond(200));

        for (String id :
                new String[] {"pay_01ARZ3NDEKTSV4RRFFQ69G5FAX", "not-a-payment", foreignId}) {
            ResponseEntity<String> response = getWithApiKey("/v1/payments/" + id);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).contains("\"code\":\"resource_not_found\"");
        }
    }

    @Test
    void listUsesStableCursorPaginationWithoutLeakingOtherMerchants() throws Exception {
        String newest = "pay_01ARZ3NDEKTSV4RRFFQ69G5FAX";
        String tiedSecond = "pay_01ARZ3NDEKTSV4RRFFQ69G5FAW";
        String oldest = "pay_01ARZ3NDEKTSV4RRFFQ69G5FAV";
        savePayment(oldest, MERCHANT_ID, Instant.ofEpochSecond(100));
        savePayment(tiedSecond, MERCHANT_ID, Instant.ofEpochSecond(200));
        savePayment(newest, MERCHANT_ID, Instant.ofEpochSecond(200));
        savePayment(
                "pay_01ARZ3NDEKTSV4RRFFQ69G5FAY", OTHER_MERCHANT_ID, Instant.ofEpochSecond(300));

        JsonNode first =
                body(getWithApiKey("/v1/payments?limit=2&created.gte=100&created.lte=200"));
        assertThat(first.path("object").asText()).isEqualTo("list");
        assertThat(first.path("data").size()).isEqualTo(2);
        assertThat(first.path("data").get(0).path("id").asText()).isEqualTo(newest);
        assertThat(first.path("data").get(1).path("id").asText()).isEqualTo(tiedSecond);
        assertThat(first.path("has_more").asBoolean()).isTrue();
        assertThat(first.path("url").asText()).isEqualTo("/v1/payments");

        JsonNode second =
                body(
                        getWithApiKey(
                                "/v1/payments?limit=2&created.gte=100&created.lte=200&starting_after="
                                        + tiedSecond));
        assertThat(second.path("data").size()).isEqualTo(1);
        assertThat(second.path("data").get(0).path("id").asText()).isEqualTo(oldest);
        assertThat(second.path("has_more").asBoolean()).isFalse();
    }

    @Test
    void invalidLimitsRangesAndCursorsReturn400() {
        String outsideRange = "pay_01ARZ3NDEKTSV4RRFFQ69G5FAV";
        String foreign = "pay_01ARZ3NDEKTSV4RRFFQ69G5FAW";
        savePayment(outsideRange, MERCHANT_ID, Instant.ofEpochSecond(99));
        savePayment(foreign, OTHER_MERCHANT_ID, Instant.ofEpochSecond(150));

        assertBadRequest("/v1/payments?limit=101", "invalid_limit");
        assertBadRequest("/v1/payments?created.gte=-1", "invalid_created");
        assertBadRequest("/v1/payments?created.gte=200&created.lte=100", "invalid_created_range");
        assertBadRequest("/v1/payments?starting_after=not-a-payment", "invalid_cursor");
        assertBadRequest("/v1/payments?starting_after=" + foreign, "invalid_cursor");
        assertBadRequest(
                "/v1/payments?created.gte=100&created.lte=200&starting_after=" + outsideRange,
                "invalid_cursor");
    }

    @Test
    void missingAndInvalidApiKeysReturn401() {
        ResponseEntity<String> missing = restTemplate.getForEntity("/v1/payments", String.class);
        MerchantApiContract.assertResponseConforms(HttpMethod.GET, "/v1/payments", missing);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(missing.getBody()).contains("authentication_error");
        assertThat(missing.getHeaders().getFirst("X-Request-Id")).startsWith("req_");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("invalid-key");
        ResponseEntity<String> invalid =
                restTemplate.exchange(
                        "/v1/payments", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(invalid.getBody()).contains("invalid_api_key");
    }

    private Payment savePayment(String externalId, String merchantId, Instant createdAt) {
        Payment payment =
                paymentRepository.saveAndFlush(
                        new Payment(
                                UUID.randomUUID(),
                                externalId,
                                "cs_" + externalId.substring(4),
                                merchantId,
                                "order-" + externalId,
                                19900L,
                                "DKK",
                                PaymentStatus.CAPTURED,
                                new PaymentMethodDetails("visa", "4242", 12, 2027, "DK"),
                                Map.of("cart_id", "abc123")));
        payment.setAmountCaptured(19900L);
        paymentRepository.saveAndFlush(payment);
        jdbc.update(
                "UPDATE payments SET created_at = ?, updated_at = ? WHERE external_id = ?",
                Timestamp.from(createdAt),
                Timestamp.from(createdAt),
                externalId);
        entityManager.clear();
        return payment;
    }

    private ResponseEntity<String> getWithApiKey(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(API_KEY);
        ResponseEntity<String> response =
                restTemplate.exchange(
                        path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        MerchantApiContract.assertConforms(HttpMethod.GET, path, headers, null, response);
        return response;
    }

    private JsonNode body(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    private void assertBadRequest(String path, String code) {
        ResponseEntity<String> response = getWithApiKey(path);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"code\":\"" + code + "\"");
        assertThat(response.getHeaders().getFirst("X-Request-Id")).startsWith("req_");
    }
}
