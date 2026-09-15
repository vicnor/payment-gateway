package com.gateway.payment.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.payment.PaymentServiceApplication;
import com.gateway.shared.security.ApiKeyFormat;
import com.gateway.shared.security.ratelimit.ApiKeyRateLimiter;
import com.gateway.shared.testing.AbstractPostgresIT;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/** Full-stack contract coverage for shared Merchant API rate limiting. */
@TestInstance(Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = PaymentServiceApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(PaymentMerchantRateLimitIT.RateLimitTestConfig.class)
@TestPropertySource(properties = "gateway.payment.outbox.publisher.enabled=false")
class PaymentMerchantRateLimitIT extends AbstractPostgresIT {

    private static final String MERCHANT_ID = "mer_rate_limit_test";
    private static final String API_KEY_A = "sk_test_01ARZ3NDEKTSV4RRFFQ69G5FAV_" + "a".repeat(43);
    private static final String API_KEY_B = "sk_test_01BRZ3NDEKTSV4RRFFQ69G5FAV_" + "b".repeat(43);
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
    @Autowired ObjectMapper objectMapper;

    @BeforeAll
    void stubMerchantService() {
        stubApiKey(API_KEY_A, "018f4d8d-86a0-7000-8000-000000000001");
        stubApiKey(API_KEY_B, "018f4d8d-86a0-7000-8000-000000000002");
    }

    @AfterAll
    void stopWireMock() {
        wireMock.stop();
    }

    @Test
    void enforcesBurstAndKeepsApiKeysIsolated() throws Exception {
        ResponseEntity<String> first = getPayments(API_KEY_A);
        ResponseEntity<String> second = getPayments(API_KEY_A);
        ResponseEntity<String> rejected = getPayments(API_KEY_A);
        ResponseEntity<String> otherKey = getPayments(API_KEY_B);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getHeaders().getFirst("RateLimit-Limit")).isEqualTo("2");
        assertThat(first.getHeaders().getFirst("RateLimit-Remaining")).isEqualTo("1");
        assertThat(first.getHeaders().getFirst("RateLimit-Reset")).isEqualTo("1");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getHeaders().getFirst("RateLimit-Remaining")).isEqualTo("0");

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getHeaders().getFirst("RateLimit-Limit")).isEqualTo("2");
        assertThat(rejected.getHeaders().getFirst("RateLimit-Remaining")).isEqualTo("0");
        assertThat(rejected.getHeaders().getFirst("RateLimit-Reset")).isEqualTo("2");
        assertThat(rejected.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(rejected.getHeaders().getFirst("X-Request-Id")).startsWith("req_");
        JsonNode error = objectMapper.readTree(rejected.getBody()).path("error");
        assertThat(error.path("type").asText()).isEqualTo("rate_limit_error");
        assertThat(error.path("code").asText()).isEqualTo("rate_limit_exceeded");
        assertThat(error.path("request_id").asText()).startsWith("req_");

        assertThat(otherKey.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(otherKey.getHeaders().getFirst("RateLimit-Remaining")).isEqualTo("1");
    }

    private void stubApiKey(String apiKey, String keyId) {
        String prefix = ApiKeyFormat.extractPrefix(apiKey);
        String hash = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(apiKey);
        stubFor(
                get(urlEqualTo("/internal/v1/api-keys/" + prefix))
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
                                                                UUID.fromString(keyId),
                                                                MERCHANT_ID,
                                                                prefix,
                                                                hash))));
    }

    private ResponseEntity<String> getPayments(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        return restTemplate.exchange(
                "/v1/payments", HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RateLimitTestConfig {

        @Bean
        ApiKeyRateLimiter apiKeyRateLimiter() {
            Ticker fixedTicker = () -> 0L;
            return new ApiKeyRateLimiter(1, 2, Duration.ofMinutes(10), fixedTicker);
        }
    }
}
