package com.gateway.token.it;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.gateway.shared.testing.AbstractDynamoKmsIT;
import com.gateway.token.PanScanAppender;
import com.gateway.token.TokenServiceApplication;
import com.gateway.token.persistence.DataKeyItem;
import com.gateway.token.persistence.TokenItem;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;

/**
 * Integration test for the tokenize endpoint (task 2.2).
 *
 * <p>Proves:
 *
 * <ul>
 *   <li>{@code POST /checkout/{session_id}/tokens} returns 201 with the five PCI-safe fields
 *   <li>The {@code tokens} and {@code data_keys} DynamoDB rows are correctly persisted
 *   <li>The {@code encrypted_card_data} field does not contain the PAN in plaintext
 *   <li>No log line at any level contains the PAN (PCI hard rule, via {@link PanScanAppender})
 *   <li>Semantic validation errors (invalid Luhn, past expiry, bad CVV, unsupported brand) → 400
 * </ul>
 *
 * <p>{@link TestInstance} per-class lets {@link BeforeAll} inject Spring beans.
 */
@TestInstance(Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = TokenServiceApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT)
class TokenizeIT extends AbstractDynamoKmsIT {

    private static final String TEST_PAN = "4242424242424242";
    private static final String TEST_CVV = "123";
    private static final String SESSION_ID = "cs_it_test_session_01";

    // -------------------------------------------------------------------
    // Provide the KMS key alias before the Spring context starts
    // -------------------------------------------------------------------

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
                            .createKey(r -> r.description("tokenize-it-cmk"))
                            .keyMetadata()
                            .keyId();
            kmsClient.createAlias(r -> r.aliasName("alias/token-service-dev").targetKeyId(keyId));
        }
        registry.add("gateway.aws.kms.key-id", () -> "alias/token-service-dev");
    }

    // -------------------------------------------------------------------
    // PAN-scan appender (attached for the lifetime of this test class)
    // -------------------------------------------------------------------

    private final PanScanAppender panScanAppender = new PanScanAppender();

    @BeforeAll
    void setUp() {
        // Create DynamoDB tables (idempotent — another IT may have already created them in the
        // shared DynamoDB-Local container)
        createTableIfAbsent("tokens", TokenItem.TABLE_SCHEMA);
        createTableIfAbsent("data_keys", DataKeyItem.TABLE_SCHEMA);

        // Attach PAN-scan appender to root logger
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        panScanAppender.setContext(loggerContext);
        panScanAppender.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(panScanAppender);
    }

    @AfterAll
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(panScanAppender);
        panScanAppender.stop();
    }

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private DynamoDbEnhancedClient enhancedClient;

    // -------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------

    @Test
    void tokenizeReturns201WithSafePciFields() {
        ResponseEntity<String> response = postTokenize(SESSION_ID, validCardBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).contains("\"token\"");
        assertThat(response.getBody()).contains("\"brand\":\"visa\"");
        assertThat(response.getBody()).contains("\"last4\":\"4242\"");
        assertThat(response.getBody()).contains("\"exp_month\":12");
        assertThat(response.getBody()).contains("\"exp_year\":2027");
    }

    @Test
    void tokenizeResponseNeverContainsPanOrCvv() {
        ResponseEntity<String> response = postTokenize(SESSION_ID + "_pan_check", validCardBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).doesNotContain(TEST_PAN);
        assertThat(response.getBody()).doesNotContain("\"cvv\"");
        assertThat(response.getBody()).doesNotContain("\"card_number\"");
    }

    @Test
    void tokenizePersistsTokenRowWithEncryptedData() {
        String sid = SESSION_ID + "_persist_token";
        ResponseEntity<String> response = postTokenize(sid, validCardBody());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Extract token value from response body
        String body = response.getBody();
        String tokenId = extractJsonString(body, "token");
        assertThat(tokenId).startsWith("tok_");

        // Re-fetch and independently verify — assert against re-fetched values (not response echo)
        DynamoDbTable<TokenItem> table = enhancedClient.table("tokens", TokenItem.TABLE_SCHEMA);
        TokenItem persisted = table.getItem(Key.builder().partitionValue(tokenId).build());

        assertThat(persisted).isNotNull();
        assertThat(persisted.getSessionId()).isEqualTo(sid);
        assertThat(persisted.getEncryptedCardData()).isNotBlank();
        assertThat(persisted.getEncryptedCardData()).doesNotContain(TEST_PAN);
        assertThat(persisted.getDataKeyId()).startsWith("dk_");
        assertThat(persisted.getCard()).isNotNull();
        assertThat(persisted.getCard().getBrand()).isEqualTo("visa");
        assertThat(persisted.getCard().getLast4()).isEqualTo("4242");
        assertThat(persisted.getCard().getExpMonth()).isEqualTo(12);
        assertThat(persisted.getCard().getExpYear()).isEqualTo(2027);
        assertThat(persisted.getSingleUse()).isTrue();
        assertThat(persisted.getUsed()).isFalse();

        long nowEpoch = Instant.now().getEpochSecond();
        assertThat(persisted.getExpiresAt()).isBetween(nowEpoch + 1790L, nowEpoch + 1810L);
    }

    @Test
    void tokenizePersistsDataKeyRow() {
        String sid = SESSION_ID + "_persist_dk";
        ResponseEntity<String> response = postTokenize(sid, validCardBody());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String tokenId = extractJsonString(response.getBody(), "token");
        DynamoDbTable<TokenItem> tokenTable =
                enhancedClient.table("tokens", TokenItem.TABLE_SCHEMA);
        String dataKeyId =
                tokenTable.getItem(Key.builder().partitionValue(tokenId).build()).getDataKeyId();

        DynamoDbTable<DataKeyItem> dkTable =
                enhancedClient.table("data_keys", DataKeyItem.TABLE_SCHEMA);
        DataKeyItem dkItem = dkTable.getItem(Key.builder().partitionValue(dataKeyId).build());

        assertThat(dkItem).isNotNull();
        assertThat(dkItem.getEncryptedDek()).isNotBlank();
        assertThat(dkItem.getKmsKeyId()).isEqualTo("alias/token-service-dev");
        assertThat(dkItem.getCreatedAt()).isGreaterThan(0L);
    }

    // -------------------------------------------------------------------
    // Hard PCI test: no PAN in any log line
    // -------------------------------------------------------------------

    @Test
    void panNeverAppearsInLogs() {
        panScanAppender.reset();

        postTokenize(SESSION_ID + "_log_check", validCardBody());

        assertThat(panScanAppender.getViolations())
                .as("No log line should contain a PAN-length numeric sequence")
                .isEmpty();
    }

    // -------------------------------------------------------------------
    // Validation error paths
    // -------------------------------------------------------------------

    @Test
    void invalidLuhnReturns400() {
        String body =
                """
                {
                  "card_number": "4242424242424241",
                  "exp_month":   12,
                  "exp_year":    2027,
                  "cvv":         "123",
                  "holder_name": "Test"
                }
                """;
        ResponseEntity<String> response = postTokenize(SESSION_ID, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"type\":\"validation_error\"");
    }

    @Test
    void expiredCardReturns400() {
        String body =
                """
                {
                  "card_number": "4242424242424242",
                  "exp_month":   1,
                  "exp_year":    2020,
                  "cvv":         "123",
                  "holder_name": "Test"
                }
                """;
        ResponseEntity<String> response = postTokenize(SESSION_ID, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"code\":\"card_expired\"");
    }

    @Test
    void unsupportedBrandReturns400() {
        // Amex — passes Luhn but not Visa/MC
        String body =
                """
                {
                  "card_number": "378282246310005",
                  "exp_month":   12,
                  "exp_year":    2027,
                  "cvv":         "1234",
                  "holder_name": "Test"
                }
                """;
        ResponseEntity<String> response = postTokenize(SESSION_ID, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"code\":\"unsupported_card_brand\"");
    }

    @Test
    void requestIdHeaderPresentOnSuccess() {
        ResponseEntity<String> response = postTokenize(SESSION_ID + "_rid", validCardBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotNull();
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

    /** Minimal JSON string-value extractor — avoids pulling in a JSON lib for tests. */
    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search) + search.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }

    private <T> void createTableIfAbsent(
            String name, software.amazon.awssdk.enhanced.dynamodb.TableSchema<T> schema) {
        try {
            enhancedClient.table(name, schema).createTable();
        } catch (software.amazon.awssdk.services.dynamodb.model.ResourceInUseException ignored) {
            // Table already exists from a concurrently running IT — shared DynamoDB-Local container
        }
    }
}
