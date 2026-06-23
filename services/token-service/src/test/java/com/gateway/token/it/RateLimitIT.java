package com.gateway.token.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.shared.testing.AbstractDynamoKmsIT;
import com.gateway.token.TokenServiceApplication;
import com.gateway.token.persistence.DataKeyItem;
import com.gateway.token.persistence.RateLimitStore;
import com.gateway.token.persistence.TokenItem;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.kms.KmsClient;

/**
 * Integration test for the per-session rate-limit on {@code POST /checkout/{sessionId}/tokens}.
 *
 * <p>Uses {@code gateway.token.rate-limit.max-attempts=3} to keep the test fast (only 4 requests
 * needed to prove the limit). Verifies the DynamoDB counter by re-reading the {@code
 * token_rate_limits} item independently rather than trusting the HTTP response alone.
 */
@TestInstance(Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = TokenServiceApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        properties = {
            "gateway.token.rate-limit.max-attempts=3",
            "gateway.token.rate-limit.window-seconds=60"
        })
class RateLimitIT extends AbstractDynamoKmsIT {

    private static final String TEST_SESSION = "cs_rl_it_session_01";
    private static final String OTHER_SESSION = "cs_rl_it_session_02";

    @DynamicPropertySource
    static void provideKmsKeyId(DynamicPropertyRegistry registry) {
        try (KmsClient kmsClient =
                KmsClient.builder()
                        .region(Region.of("eu-north-1"))
                        .endpointOverride(URI.create(AbstractDynamoKmsIT.kmsEndpoint()))
                        .credentialsProvider(
                                StaticCredentialsProvider.create(
                                        AwsBasicCredentials.create("test", "test")))
                        .build()) {
            String keyId =
                    kmsClient
                            .createKey(r -> r.description("rate-limit-it-cmk"))
                            .keyMetadata()
                            .keyId();
            try {
                kmsClient.createAlias(
                        r -> r.aliasName("alias/token-service-dev").targetKeyId(keyId));
            } catch (software.amazon.awssdk.services.kms.model.AlreadyExistsException ignored) {
                // Shared LocalStack container — alias may already exist from another IT
            }
        }
        registry.add("gateway.aws.kms.key-id", () -> "alias/token-service-dev");
    }

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private DynamoDbClient dynamoDbClient;
    @Autowired private DynamoDbEnhancedClient enhancedClient;

    @BeforeAll
    void setUp() {
        createTableIfAbsent("tokens", TokenItem.TABLE_SCHEMA);
        createTableIfAbsent("data_keys", DataKeyItem.TABLE_SCHEMA);
        createRateLimitTableIfAbsent();
    }

    // -------------------------------------------------------------------
    // Rate-limit enforcement
    // -------------------------------------------------------------------

    @Test
    void attemptsUpToLimitSucceed() {
        // max-attempts=3: three POSTs should all return 201
        for (int i = 1; i <= 3; i++) {
            ResponseEntity<String> response =
                    postTokenize(TEST_SESSION + "_success", validCardBody());
            assertThat(response.getStatusCode())
                    .as("attempt %d should succeed", i)
                    .isEqualTo(HttpStatus.CREATED);
        }
    }

    @Test
    void attemptBeyondLimitReturns429() {
        String sessionId = TEST_SESSION + "_limit";

        // Fill up the limit (max-attempts=3)
        for (int i = 0; i < 3; i++) {
            postTokenize(sessionId, validCardBody());
        }

        // 4th attempt should be rejected
        ResponseEntity<String> response = postTokenize(sessionId, validCardBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).contains("\"type\":\"rate_limit_error\"");
        assertThat(response.getBody()).contains("\"code\":\"rate_limit_exceeded\"");
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("60");
    }

    @Test
    void databaseCounterReflectsActualAttemptCount() {
        String sessionId = TEST_SESSION + "_counter_check";

        // Make exactly 2 attempts (less than limit so the condition keeps passing)
        postTokenize(sessionId, validCardBody());
        postTokenize(sessionId, validCardBody());

        // Independently verify the counter in DynamoDB (not from the HTTP response)
        GetItemResponse item =
                dynamoDbClient.getItem(
                        GetItemRequest.builder()
                                .tableName(RateLimitStore.TABLE_NAME)
                                .key(
                                        Map.of(
                                                RateLimitStore.ATTR_SESSION_ID,
                                                AttributeValue.fromS(sessionId)))
                                .build());

        assertThat(item.hasItem()).isTrue();
        long storedCount = Long.parseLong(item.item().get(RateLimitStore.ATTR_ATTEMPTS).n());
        assertThat(storedCount).isEqualTo(2L);
    }

    @Test
    void invalidCardBodyStillIncrementsCounter() {
        String sessionId = TEST_SESSION + "_invalid_card";
        // Malformed body (invalid Luhn) — filter runs before controller
        String badCard =
                """
                {
                  "card_number": "4242424242424241",
                  "exp_month":   12,
                  "exp_year":    2027,
                  "cvv":         "123",
                  "holder_name": "Test"
                }
                """;

        // 3 invalid-card attempts should exhaust the limit
        postTokenize(sessionId, badCard);
        postTokenize(sessionId, badCard);
        postTokenize(sessionId, badCard);

        ResponseEntity<String> response = postTokenize(sessionId, validCardBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void distinctSessionsHaveIndependentCounters() {
        // Exhaust limit on session A
        for (int i = 0; i < 3; i++) {
            postTokenize(OTHER_SESSION + "_a", validCardBody());
        }
        assertThat(postTokenize(OTHER_SESSION + "_a", validCardBody()).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // Session B is unaffected
        ResponseEntity<String> response = postTokenize(OTHER_SESSION + "_b", validCardBody());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void rateLimitResponseHasSecurityHeaders() {
        String sessionId = TEST_SESSION + "_headers";
        for (int i = 0; i < 3; i++) {
            postTokenize(sessionId, validCardBody());
        }

        ResponseEntity<String> response = postTokenize(sessionId, validCardBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private ResponseEntity<String> postTokenize(String sessionId, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(
                "/checkout/" + sessionId + "/tokens",
                new HttpEntity<>(body, headers),
                String.class);
    }

    private static String validCardBody() {
        return """
               {
                 "card_number": "4242424242424242",
                 "exp_month":   12,
                 "exp_year":    2027,
                 "cvv":         "123",
                 "holder_name": "Test Cardholder"
               }
               """;
    }

    private void createRateLimitTableIfAbsent() {
        try {
            dynamoDbClient.createTable(
                    b ->
                            b.tableName(RateLimitStore.TABLE_NAME)
                                    .keySchema(
                                            k ->
                                                    k.attributeName(RateLimitStore.ATTR_SESSION_ID)
                                                            .keyType(
                                                                    software.amazon.awssdk.services
                                                                            .dynamodb.model.KeyType
                                                                            .HASH))
                                    .attributeDefinitions(
                                            a ->
                                                    a.attributeName(RateLimitStore.ATTR_SESSION_ID)
                                                            .attributeType(
                                                                    software.amazon.awssdk.services
                                                                            .dynamodb.model
                                                                            .ScalarAttributeType.S))
                                    .billingMode(
                                            software.amazon.awssdk.services.dynamodb.model
                                                    .BillingMode.PAY_PER_REQUEST));
        } catch (software.amazon.awssdk.services.dynamodb.model.ResourceInUseException ignored) {
            // Table already created by another IT in the shared DynamoDB-Local container
        }
    }

    private <T> void createTableIfAbsent(
            String name, software.amazon.awssdk.enhanced.dynamodb.TableSchema<T> schema) {
        try {
            enhancedClient.table(name, schema).createTable();
        } catch (software.amazon.awssdk.services.dynamodb.model.ResourceInUseException ignored) {
            // Already exists
        }
    }
}
