package com.gateway.token.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.gateway.shared.testing.AbstractDynamoKmsIT;
import com.gateway.token.AuditCaptureAppender;
import com.gateway.token.PanScanAppender;
import com.gateway.token.TokenServiceApplication;
import com.gateway.token.persistence.DataKeyItem;
import com.gateway.token.persistence.TokenItem;
import java.net.URI;
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
import org.springframework.test.context.TestPropertySource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;

/**
 * Integration test for the detokenize endpoint (task 2.3).
 *
 * <p>Proves:
 *
 * <ul>
 *   <li>Tokenize → detokenize returns the original PAN and expiry
 *   <li>The {@code tokens} row is marked {@code used: true} after detokenization (re-fetched)
 *   <li>A second detokenize of the same token returns 409
 *   <li>Detokenizing a non-existent token returns 404
 *   <li>Calling with an unknown caller or wrong secret returns 403
 *   <li>No log line at any level contains the PAN (PCI hard rule)
 *   <li>One audit entry is written per successful detokenize call
 * </ul>
 */
@TestInstance(Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = TokenServiceApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        properties = {
            "gateway.token.internal.callers[0].id=payment-service",
            "gateway.token.internal.callers[0].secret=it-secret"
        })
class DetokenizeIT extends AbstractDynamoKmsIT {

    private static final String TEST_PAN = "4242424242424242";
    private static final String SESSION_ID = "cs_detok_it_session_01";
    private static final String CALLER_ID = "payment-service";
    private static final String CALLER_SECRET = "it-secret";

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
                            .createKey(r -> r.description("detokenize-it-cmk"))
                            .keyMetadata()
                            .keyId();
            try {
                kmsClient.createAlias(
                        r -> r.aliasName("alias/token-service-dev").targetKeyId(keyId));
            } catch (software.amazon.awssdk.services.kms.model.AlreadyExistsException ignored) {
                // TokenizeIT may have already created this alias in the shared LocalStack container
            }
        }
        registry.add("gateway.aws.kms.key-id", () -> "alias/token-service-dev");
    }

    private final PanScanAppender panScanAppender = new PanScanAppender();
    private final AuditCaptureAppender auditAppender = new AuditCaptureAppender();

    @BeforeAll
    void setUp() {
        createTableIfAbsent("tokens", TokenItem.TABLE_SCHEMA);
        createTableIfAbsent("data_keys", DataKeyItem.TABLE_SCHEMA);

        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();

        panScanAppender.setContext(ctx);
        panScanAppender.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(panScanAppender);

        auditAppender.setContext(ctx);
        auditAppender.start();
        ((Logger) LoggerFactory.getLogger("com.gateway.token.audit")).addAppender(auditAppender);
    }

    @AfterAll
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(panScanAppender);
        panScanAppender.stop();

        ((Logger) LoggerFactory.getLogger("com.gateway.token.audit")).detachAppender(auditAppender);
        auditAppender.stop();
    }

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private DynamoDbEnhancedClient enhancedClient;

    // -------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------

    @Test
    void detokenizeReturnsPanAndExpiry() {
        String tokenId = tokenize(SESSION_ID + "_happy");

        ResponseEntity<String> response = postDetokenize(tokenId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"pan\":\"" + TEST_PAN + "\"");
        assertThat(response.getBody()).contains("\"exp_month\":12");
        assertThat(response.getBody()).contains("\"exp_year\":2027");
    }

    @Test
    void detokenizeMarksTokenAsUsed() {
        String tokenId = tokenize(SESSION_ID + "_mark_used");

        ResponseEntity<String> response = postDetokenize(tokenId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        DynamoDbTable<TokenItem> table = enhancedClient.table("tokens", TokenItem.TABLE_SCHEMA);
        TokenItem persisted = table.getItem(Key.builder().partitionValue(tokenId).build());

        assertThat(persisted).isNotNull();
        assertThat(persisted.getUsed()).isTrue();
    }

    // -------------------------------------------------------------------
    // PCI and audit assertions
    // -------------------------------------------------------------------

    @Test
    void panNeverAppearsInLogs() {
        panScanAppender.reset();

        String tokenId = tokenize(SESSION_ID + "_pan_log");
        postDetokenize(tokenId);

        assertThat(panScanAppender.getViolations())
                .as("No log line should contain a PAN-length numeric sequence")
                .isEmpty();
    }

    @Test
    void auditEntryWrittenOnSuccess() {
        auditAppender.reset();

        String tokenId = tokenize(SESSION_ID + "_audit");
        ResponseEntity<String> response = postDetokenize(tokenId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(auditAppender.getEntries()).hasSize(1);
        String entry = auditAppender.getEntries().get(0);
        assertThat(entry).contains("\"event\":\"detokenize\"");
        assertThat(entry).contains("\"token_id\":\"" + tokenId + "\"");
        assertThat(entry).contains("\"caller\":\"" + CALLER_ID + "\"");
        assertThat(entry).contains("\"timestamp\":");
    }

    // -------------------------------------------------------------------
    // Error paths
    // -------------------------------------------------------------------

    @Test
    void secondDetokenizeReturns409() {
        String tokenId = tokenize(SESSION_ID + "_double_use");

        assertThat(postDetokenize(tokenId).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> second = postDetokenize(tokenId);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("\"code\":\"token_already_used\"");
    }

    @Test
    void nonExistentTokenReturns404() {
        ResponseEntity<String> response = postDetokenize("tok_nonexistent_00000000000000000");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("\"type\":\"not_found\"");
    }

    @Test
    void wrongCallerSecretReturnsForbidden() {
        String tokenId = tokenize(SESSION_ID + "_bad_secret");

        ResponseEntity<String> response = postDetokenize(tokenId, CALLER_ID, "wrong-secret");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("\"type\":\"permission_error\"");
    }

    @Test
    void unknownCallerReturnsForbidden() {
        String tokenId = tokenize(SESSION_ID + "_unknown_caller");

        ResponseEntity<String> response = postDetokenize(tokenId, "unknown-service", CALLER_SECRET);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private String tokenize(String sessionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        "/checkout/" + sessionId + "/tokens",
                        new HttpEntity<>(validCardBody(), headers),
                        String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertNotNull(response.getBody());
        return extractJsonString(response.getBody(), "token");
    }

    private ResponseEntity<String> postDetokenize(String tokenId) {
        return postDetokenize(tokenId, CALLER_ID, CALLER_SECRET);
    }

    private ResponseEntity<String> postDetokenize(String tokenId, String callerId, String secret) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Caller-Service", callerId);
        headers.set("X-Internal-Token", secret);
        return restTemplate.postForEntity(
                "/internal/v1/tokens/" + tokenId + "/detokenize",
                new HttpEntity<>(null, headers),
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
            // Table already exists from a concurrently running IT
        }
    }
}
