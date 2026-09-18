package com.gateway.checkout.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.checkout.CheckoutServiceApplication;
import com.gateway.checkout.persistence.CheckoutSessionItem;
import com.gateway.checkout.persistence.IdempotencyKeyItem;
import com.gateway.checkout.persistence.MerchantReferenceItem;
import com.gateway.shared.security.ApiKeyFormat;
import com.gateway.shared.testing.AbstractDynamoKmsIT;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.NotFoundException;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = CheckoutServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CheckoutSessionMerchantIT extends AbstractDynamoKmsIT {
    private static final String MERCHANT_ID = "mer_test_checkout";
    private static final String API_KEY = "sk_test_01ARZ3NDEKTSV4RRFFQ69G5FAV_" + "a".repeat(43);
    private static final String PREFIX = ApiKeyFormat.extractPrefix(API_KEY);
    private static final String HASH =
            Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(API_KEY);
    private static final String OTHER_MERCHANT_ID = "mer_test_checkout_other";
    private static final String OTHER_API_KEY =
            "sk_test_01BRZ3NDEKTSV4RRFFQ69G5FAV_" + "b".repeat(43);
    private static final String OTHER_PREFIX = ApiKeyFormat.extractPrefix(OTHER_API_KEY);
    private static final String OTHER_HASH =
            Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(OTHER_API_KEY);
    private static final WireMockServer WIREMOCK;

    static {
        WIREMOCK = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        WIREMOCK.start();
        WireMock.configureFor("localhost", WIREMOCK.port());
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("shared.security.merchant-service.base-url", WIREMOCK::baseUrl);
        registry.add("gateway.checkout.base-url", () -> "https://checkout.test");
        registry.add("gateway.aws.kms.key-id", () -> "alias/checkout-idempotency-test");
    }

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;
    @Autowired KmsClient kms;

    @Autowired
    @Qualifier("checkoutSessionsTable")
    DynamoDbTable<CheckoutSessionItem> sessions;

    @Autowired
    @Qualifier("checkoutIdempotencyKeysTable")
    DynamoDbTable<IdempotencyKeyItem> idempotency;

    @Autowired
    @Qualifier("checkoutMerchantReferencesTable")
    DynamoDbTable<MerchantReferenceItem> references;

    @BeforeAll
    void setup() {
        create(sessions);
        create(idempotency);
        create(references);
        try {
            kms.describeKey(r -> r.keyId("alias/checkout-idempotency-test"));
        } catch (NotFoundException missing) {
            String keyId = kms.createKey().keyMetadata().keyId();
            kms.createAlias(r -> r.aliasName("alias/checkout-idempotency-test").targetKeyId(keyId));
        }
    }

    @BeforeEach
    void resetMerchantService() {
        WireMock.reset();
        stubApiKey(PREFIX, HASH, MERCHANT_ID);
        stubMerchant(MERCHANT_ID, "TEST", "ACTIVE");
    }

    private static void stubApiKey(String prefix, String hash, String merchantId) {
        stubFor(
                WireMock.get(WireMock.urlEqualTo("/internal/v1/api-keys/" + prefix))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                    {"keys":[{"id":"%s","merchant_id":"%s","key_prefix":"%s","key_hash":"%s","mode":"TEST"}]}
                    """
                                                        .formatted(
                                                                UUID.randomUUID(),
                                                                merchantId,
                                                                prefix,
                                                                hash))));
    }

    private static void stubMerchant(String merchantId, String mode, String status) {
        stubFor(
                WireMock.get(WireMock.urlEqualTo("/internal/v1/merchants/" + merchantId))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                    {"id":"%s","name":"Test","return_url_pattern":"^https://merchant\\\\.example/.*$","cancel_url_pattern":"^https://merchant\\\\.example/.*$","branding":{"logo_url":"https://merchant.example/logo.png","accent_color":"#123456"},"mode":"%s","status":"%s"}
                    """
                                                        .formatted(merchantId, mode, status))));
    }

    @AfterAll
    static void stop() {
        WIREMOCK.stop();
    }

    @Test
    void createsAndReplaysExactlyOneSession() throws Exception {
        String key = UUID.randomUUID().toString();
        ResponseEntity<String> first = post("/v1/checkout-sessions", key, validBody("order-it-1"));
        ResponseEntity<String> replay = post("/v1/checkout-sessions", key, validBody("order-it-1"));
        assertThat(first.getStatusCode().value()).withFailMessage(first.getBody()).isEqualTo(201);
        assertThat(first.getHeaders().getFirst("X-Request-Id")).startsWith("req_");
        assertThat(first.getHeaders().getFirst("RateLimit-Limit")).isEqualTo("200");
        assertThat(first.getHeaders().getFirst("RateLimit-Remaining")).isNotBlank();
        assertThat(first.getHeaders().getFirst("RateLimit-Reset")).isNotBlank();
        assertThat(replay.getStatusCode().value()).isEqualTo(201);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replay")).isEqualTo("true");
        assertThat(mapper.readTree(replay.getBody())).isEqualTo(mapper.readTree(first.getBody()));
        JsonNode body = mapper.readTree(first.getBody());
        assertThat(body.path("url").asText()).startsWith("https://checkout.test/checkout/cs_");
        assertThat(body.path("livemode").asBoolean()).isFalse();
        ResponseEntity<String> retrieved = get("/v1/checkout-sessions/" + body.path("id").asText());
        assertThat(retrieved.getStatusCode().value()).isEqualTo(200);
        assertThat(retrieved.getHeaders().getFirst("RateLimit-Limit")).isEqualTo("200");
        assertThat(mapper.readTree(retrieved.getBody()).path("id").asText())
                .isEqualTo(body.path("id").asText());
        String secret =
                body.path("url").asText().substring(body.path("url").asText().indexOf("?k=") + 3);
        assertThat(
                        sessions.getItem(
                                        Key.builder()
                                                .partitionValue(body.path("id").asText())
                                                .build())
                                .getSessionSecretHash())
                .doesNotContain(secret);
        assertThat(idempotency.scan().items())
                .allSatisfy(
                        item -> {
                            assertThat(String.valueOf(item.getResponseBody()))
                                    .doesNotContain(secret);
                            assertThat(String.valueOf(item.getEncryptedSecret()))
                                    .doesNotContain(secret);
                        });
    }

    @Test
    void rejectsDuplicateReferenceAndDisallowedUrl() {
        ResponseEntity<String> created =
                post(
                        "/v1/checkout-sessions",
                        UUID.randomUUID().toString(),
                        validBody("order-it-duplicate"));
        assertThat(created.getStatusCode().value())
                .withFailMessage(created.getBody())
                .isEqualTo(201);
        ResponseEntity<String> duplicate =
                post(
                        "/v1/checkout-sessions",
                        UUID.randomUUID().toString(),
                        validBody("order-it-duplicate"));
        assertThat(duplicate.getStatusCode().value()).isEqualTo(409);
        assertThat(duplicate.getBody()).contains("merchant_reference_exists");

        stubApiKey(OTHER_PREFIX, OTHER_HASH, OTHER_MERCHANT_ID);
        stubMerchant(OTHER_MERCHANT_ID, "TEST", "ACTIVE");
        ResponseEntity<String> sameReferenceForOtherMerchant =
                postWithKey(
                        "/v1/checkout-sessions",
                        UUID.randomUUID().toString(),
                        validBody("order-it-duplicate"),
                        OTHER_API_KEY);
        assertThat(sameReferenceForOtherMerchant.getStatusCode().value()).isEqualTo(201);

        ResponseEntity<String> rejected =
                post(
                        "/v1/checkout-sessions",
                        UUID.randomUUID().toString(),
                        validBody("order-it-url")
                                .replace(
                                        "https://merchant.example/return",
                                        "https://evil.example/return"));
        assertThat(rejected.getStatusCode().value()).isEqualTo(400);
        assertThat(rejected.getBody()).contains("url_not_allowed");
    }

    @Test
    void requiresIdempotencyKey() {
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response =
                rest.postForEntity(
                        "/v1/checkout-sessions",
                        new HttpEntity<>(validBody("order-it-no-key"), headers),
                        String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("missing_idempotency_key");
    }

    @Test
    void sameKeyWithDifferentBodyConflicts() {
        String key = UUID.randomUUID().toString();
        ResponseEntity<String> first =
                post("/v1/checkout-sessions", key, validBody("order-it-key-a"));
        assertThat(first.getStatusCode().value()).withFailMessage(first.getBody()).isEqualTo(201);
        ResponseEntity<String> conflict =
                post("/v1/checkout-sessions", key, validBody("order-it-key-b"));
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(conflict.getBody()).contains("idempotency_key_conflict");
    }

    @Test
    void cancelAfterCompletionReturns409() throws Exception {
        ResponseEntity<String> created =
                post(
                        "/v1/checkout-sessions",
                        UUID.randomUUID().toString(),
                        validBody("order-it-completed"));
        String id = mapper.readTree(created.getBody()).path("id").asText();
        CheckoutSessionItem item = sessions.getItem(Key.builder().partitionValue(id).build());
        item.setStatus("COMPLETED");
        sessions.updateItem(item);

        ResponseEntity<String> cancelled =
                post("/v1/checkout-sessions/" + id + "/cancel", UUID.randomUUID().toString(), "");
        assertThat(cancelled.getStatusCode().value()).isEqualTo(409);
        assertThat(cancelled.getBody()).contains("invalid_checkout_session_state");
    }

    @Test
    void cancellationIsDomainAndHttpIdempotent() throws Exception {
        ResponseEntity<String> created =
                post(
                        "/v1/checkout-sessions",
                        UUID.randomUUID().toString(),
                        validBody("order-it-cancel"));
        JsonNode createdBody = mapper.readTree(created.getBody());
        String id = createdBody.path("id").asText();
        String key = UUID.randomUUID().toString();

        ResponseEntity<String> first = post("/v1/checkout-sessions/" + id + "/cancel", key, "");
        ResponseEntity<String> replay = post("/v1/checkout-sessions/" + id + "/cancel", key, "");

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(first.getHeaders().getFirst("RateLimit-Limit")).isEqualTo("200");
        assertThat(mapper.readTree(first.getBody()).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(replay.getHeaders().getFirst("Idempotent-Replay")).isEqualTo("true");
        assertThat(mapper.readTree(replay.getBody())).isEqualTo(mapper.readTree(first.getBody()));
        String secret = createdBody.path("url").asText().split("\\?k=", 2)[1];
        assertThat(idempotency.scan().items())
                .allSatisfy(
                        item ->
                                assertThat(String.valueOf(item.getResponseBody()))
                                        .doesNotContain(secret));
    }

    @Test
    void validatesAmountsCurrencyLocaleMetadataAndUnknownFields() {
        assertValidation(validBody("order-low").replace("19900", "99"), "invalid_amount");
        assertValidation(validBody("order-high").replace("19900", "100000000"), "invalid_amount");
        assertValidation(
                validBody("order-currency").replace("\"DKK\"", "\"GBP\""), "invalid_currency");
        assertValidation(
                validBody("order-locale").replace("\"da-DK\"", "\"fr-FR\""), "invalid_locale");
        assertValidation(
                validBody("order-metadata").replace("\"cart_id\":\"abc\"", "\"\":\"abc\""),
                "invalid_metadata");
        assertValidation(
                validBody("order-unknown")
                        .replace("\"amount\":19900", "\"unexpected\":true,\"amount\":19900"),
                "invalid_json");
    }

    @Test
    void acceptsAmountBoundariesSupportedCurrenciesAndLocales() {
        List<String> currencies = List.of("DKK", "EUR", "USD", "SEK", "NOK");
        for (int index = 0; index < currencies.size(); index++) {
            String body =
                    validBody("order-supported-" + index)
                            .replace("\"DKK\"", "\"" + currencies.get(index) + "\"")
                            .replace("\"da-DK\"", index % 2 == 0 ? "\"da-DK\"" : "\"en-US\"");
            if (index == 0) body = body.replace("19900", "100");
            if (index == 1) body = body.replace("19900", "99999999");

            ResponseEntity<String> response =
                    post("/v1/checkout-sessions", UUID.randomUUID().toString(), body);
            assertThat(response.getStatusCode().value())
                    .withFailMessage(response.getBody())
                    .isEqualTo(201);
        }
    }

    @Test
    void validatesUrlSchemeUserInfoAndAllowlist() {
        assertValidation(
                validBody("order-http")
                        .replace(
                                "https://merchant.example/return",
                                "http://merchant.example/return"),
                "invalid_url");
        assertValidation(
                validBody("order-userinfo")
                        .replace(
                                "https://merchant.example/return",
                                "https://user@merchant.example/return"),
                "invalid_url");
        assertValidation(
                validBody("order-host")
                        .replace(
                                "https://merchant.example/return",
                                "https://merchant.example.evil/return"),
                "url_not_allowed");
    }

    @Test
    void authenticationAndOwnershipAreEnforced() throws Exception {
        ResponseEntity<String> missingAuth =
                rest.exchange(
                        "/v1/checkout-sessions/does-not-matter",
                        HttpMethod.GET,
                        HttpEntity.EMPTY,
                        String.class);
        assertThat(missingAuth.getStatusCode().value()).isEqualTo(401);

        HttpHeaders malformed = new HttpHeaders();
        malformed.setBearerAuth("not-an-api-key");
        ResponseEntity<String> invalidAuth =
                rest.exchange(
                        "/v1/checkout-sessions/does-not-matter",
                        HttpMethod.GET,
                        new HttpEntity<>(malformed),
                        String.class);
        assertThat(invalidAuth.getStatusCode().value()).isEqualTo(401);

        String revokedKey = "sk_test_01CRZ3NDEKTSV4RRFFQ69G5FAV_" + "c".repeat(43);
        stubFor(
                WireMock.get(
                                WireMock.urlEqualTo(
                                        "/internal/v1/api-keys/"
                                                + ApiKeyFormat.extractPrefix(revokedKey)))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"keys\":[]}")));
        assertThat(
                        getWithKey("/v1/checkout-sessions/does-not-matter", revokedKey)
                                .getStatusCode()
                                .value())
                .isEqualTo(401);

        ResponseEntity<String> created =
                post(
                        "/v1/checkout-sessions",
                        UUID.randomUUID().toString(),
                        validBody("order-owned"));
        String id = mapper.readTree(created.getBody()).path("id").asText();
        stubApiKey(OTHER_PREFIX, OTHER_HASH, OTHER_MERCHANT_ID);

        assertThat(getWithKey("/v1/checkout-sessions/" + id, OTHER_API_KEY).getStatusCode().value())
                .isEqualTo(404);
        assertThat(
                        postWithKey(
                                        "/v1/checkout-sessions/" + id + "/cancel",
                                        UUID.randomUUID().toString(),
                                        "",
                                        OTHER_API_KEY)
                                .getStatusCode()
                                .value())
                .isEqualTo(404);
    }

    @Test
    void expiredSessionIsPersistedAsExpiredAndCannotBeCancelled() throws Exception {
        ResponseEntity<String> created =
                post(
                        "/v1/checkout-sessions",
                        UUID.randomUUID().toString(),
                        validBody("order-expired"));
        String id = mapper.readTree(created.getBody()).path("id").asText();
        CheckoutSessionItem item = sessions.getItem(Key.builder().partitionValue(id).build());
        item.setExpiresAt(0L);
        sessions.updateItem(item);

        ResponseEntity<String> retrieved = get("/v1/checkout-sessions/" + id);
        assertThat(retrieved.getStatusCode().value()).isEqualTo(200);
        assertThat(mapper.readTree(retrieved.getBody()).path("status").asText())
                .isEqualTo("EXPIRED");

        ResponseEntity<String> cancelled =
                post("/v1/checkout-sessions/" + id + "/cancel", UUID.randomUUID().toString(), "");
        assertThat(cancelled.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void concurrentDuplicateReferencesCreateExactlyOneSession() {
        String reference = "order-concurrent-reference";
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures =
                    IntStream.range(0, 8)
                            .mapToObj(
                                    ignored ->
                                            executor.submit(
                                                    () ->
                                                            post(
                                                                    "/v1/checkout-sessions",
                                                                    UUID.randomUUID().toString(),
                                                                    validBody(reference))))
                            .toList();
            var responses = futures.stream().map(this::join).toList();
            assertThat(responses).filteredOn(r -> r.getStatusCode().value() == 201).hasSize(1);
            assertThat(responses)
                    .filteredOn(r -> r.getStatusCode().value() == 409)
                    .hasSize(7)
                    .allSatisfy(r -> assertThat(r.getBody()).contains("merchant_reference_exists"));
        }
        assertThat(sessions.scan().items())
                .filteredOn(item -> reference.equals(item.getMerchantReference()))
                .hasSize(1);
    }

    @Test
    void concurrentSameIdempotencyKeyNeverCreatesMoreThanOneSession() {
        String key = UUID.randomUUID().toString();
        String reference = "order-concurrent-key";
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures =
                    IntStream.range(0, 8)
                            .mapToObj(
                                    ignored ->
                                            executor.submit(
                                                    () ->
                                                            post(
                                                                    "/v1/checkout-sessions",
                                                                    key,
                                                                    validBody(reference))))
                            .toList();
            var responses = futures.stream().map(this::join).toList();
            assertThat(responses)
                    .allSatisfy(
                            response ->
                                    assertThat(response.getStatusCode().value()).isIn(201, 409));
        }

        ResponseEntity<String> replay = post("/v1/checkout-sessions", key, validBody(reference));
        assertThat(replay.getStatusCode().value()).isEqualTo(201);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replay")).isEqualTo("true");
        assertThat(sessions.scan().items())
                .filteredOn(item -> reference.equals(item.getMerchantReference()))
                .hasSize(1);
    }

    @Test
    void merchantConfigurationFailuresReturnDocumentedServiceUnavailable() {
        stubFor(
                WireMock.get(WireMock.urlEqualTo("/internal/v1/merchants/" + MERCHANT_ID))
                        .willReturn(aResponse().withStatus(503)));

        ResponseEntity<String> response =
                post(
                        "/v1/checkout-sessions",
                        UUID.randomUUID().toString(),
                        validBody("order-merchant-down"));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody())
                .contains("\"type\":\"api_error\"")
                .contains("merchant_service_unavailable");
    }

    @Test
    void kmsReplayAndRetrievalFailuresReturnDocumentedServiceUnavailable() throws Exception {
        String key = UUID.randomUUID().toString();
        ResponseEntity<String> created =
                post("/v1/checkout-sessions", key, validBody("order-kms-failure"));
        String id = mapper.readTree(created.getBody()).path("id").asText();
        IdempotencyKeyItem stored =
                idempotency.scan().items().stream()
                        .filter(item -> id.equals(item.getSessionId()))
                        .findFirst()
                        .orElseThrow();
        stored.setEncryptedSecret(Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4}));
        idempotency.updateItem(stored);

        ResponseEntity<String> retrieval = get("/v1/checkout-sessions/" + id);
        assertThat(retrieval.getStatusCode().value()).isEqualTo(503);
        assertThat(retrieval.getBody()).contains("idempotency_decryption_unavailable");

        ResponseEntity<String> replay =
                post("/v1/checkout-sessions", key, validBody("order-kms-failure"));
        assertThat(replay.getStatusCode().value()).isEqualTo(503);
        assertThat(replay.getBody()).contains("idempotency_store_unavailable");
    }

    @Test
    void checkoutSecretIsNeverWrittenByRequestLogger() throws Exception {
        Logger logger =
                (Logger)
                        LoggerFactory.getLogger(
                                "com.gateway.shared.web.request.RequestLoggingFilter");
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ResponseEntity<String> created =
                    post(
                            "/v1/checkout-sessions",
                            UUID.randomUUID().toString(),
                            validBody("order-log-safety"));
            String url = mapper.readTree(created.getBody()).path("url").asText();
            String secret = url.split("\\?k=", 2)[1];

            assertThat(appender.list)
                    .allSatisfy(
                            event ->
                                    assertThat(event.getFormattedMessage()).doesNotContain(secret));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void inactiveAndModeMismatchedMerchantsAreRejected() {
        stubMerchant(MERCHANT_ID, "TEST", "INACTIVE");
        ResponseEntity<String> inactive =
                post(
                        "/v1/checkout-sessions",
                        UUID.randomUUID().toString(),
                        validBody("order-inactive"));
        assertThat(inactive.getStatusCode().value()).isEqualTo(403);
        assertThat(inactive.getBody()).contains("merchant_inactive");

        stubMerchant(MERCHANT_ID, "LIVE", "ACTIVE");
        ResponseEntity<String> mismatch =
                post(
                        "/v1/checkout-sessions",
                        UUID.randomUUID().toString(),
                        validBody("order-mode-mismatch"));
        assertThat(mismatch.getStatusCode().value()).isEqualTo(503);
        assertThat(mismatch.getBody()).contains("merchant_mode_mismatch");

        String liveKey = "sk_live_01DRZ3NDEKTSV4RRFFQ69G5FAV_" + "d".repeat(43);
        String livePrefix = ApiKeyFormat.extractPrefix(liveKey);
        String liveHash = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(liveKey);
        stubFor(
                WireMock.get(WireMock.urlEqualTo("/internal/v1/api-keys/" + livePrefix))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                    {"keys":[{"id":"%s","merchant_id":"%s","key_prefix":"%s","key_hash":"%s","mode":"LIVE"}]}
                    """
                                                        .formatted(
                                                                UUID.randomUUID(),
                                                                MERCHANT_ID,
                                                                livePrefix,
                                                                liveHash))));
        ResponseEntity<String> live =
                postWithKey(
                        "/v1/checkout-sessions",
                        UUID.randomUUID().toString(),
                        validBody("order-live"),
                        liveKey);
        assertThat(live.getStatusCode().value()).isEqualTo(403);
        assertThat(live.getBody()).contains("live_mode_unavailable");
    }

    private ResponseEntity<String> post(String path, String key, String body) {
        return postWithKey(path, key, body, API_KEY);
    }

    private ResponseEntity<String> postWithKey(
            String path, String key, String body, String apiKey) {
        HttpHeaders headers = authHeaders(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private HttpHeaders authHeaders() {
        return authHeaders(API_KEY);
    }

    private HttpHeaders authHeaders(String apiKey) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(apiKey);
        return h;
    }

    private ResponseEntity<String> get(String path) {
        return getWithKey(path, API_KEY);
    }

    private ResponseEntity<String> getWithKey(String path, String apiKey) {
        return rest.exchange(
                path, HttpMethod.GET, new HttpEntity<>(authHeaders(apiKey)), String.class);
    }

    private void assertValidation(String body, String code) {
        ResponseEntity<String> response =
                post("/v1/checkout-sessions", UUID.randomUUID().toString(), body);
        assertThat(response.getStatusCode().value())
                .withFailMessage(response.getBody())
                .isEqualTo(400);
        assertThat(response.getBody()).contains(code);
    }

    private ResponseEntity<String> join(Future<ResponseEntity<String>> future) {
        try {
            return future.get();
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static String validBody(String reference) {
        return """
        {"amount":19900,"currency":"DKK","merchant_reference":"%s","return_url":"https://merchant.example/return","cancel_url":"https://merchant.example/cancel","customer":{"email":"buyer@example.com"},"description":"Order","locale":"da-DK","metadata":{"cart_id":"abc"}}
        """
                .formatted(reference);
    }

    private static void create(DynamoDbTable<?> table) {
        try {
            table.createTable();
        } catch (ResourceInUseException ignored) {
        }
    }
}
