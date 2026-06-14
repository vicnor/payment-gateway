package com.gateway.token.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.shared.testing.AbstractDynamoKmsIT;
import com.gateway.token.TokenServiceApplication;
import com.gateway.token.persistence.DataKeyItem;
import com.gateway.token.persistence.TokenItem;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.CreateKeyResponse;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.EncryptResponse;

/**
 * Integration test for the token-service scaffold (task 2.1).
 *
 * <p>Proves:
 *
 * <ul>
 *   <li>Spring context boots against Testcontainers DynamoDB-Local and LocalStack KMS
 *   <li>{@code /actuator/health} returns 200 with {@code X-Request-Id} (shared-web wiring)
 *   <li>DynamoDB enhanced-client round-trip works against both tables
 *   <li>KMS encrypt → decrypt round-trip works
 * </ul>
 *
 * <p>Tables are created from the application's own {@link TokenItem#TABLE_SCHEMA} / {@link
 * DataKeyItem#TABLE_SCHEMA} — they are the single source of truth for key mapping.
 *
 * <p>{@link TestInstance} per-class ensures {@link BeforeAll} can use injected Spring beans.
 */
@TestInstance(Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = TokenServiceApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT)
class TokenServiceIT extends AbstractDynamoKmsIT {

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private DynamoDbEnhancedClient enhancedClient;

    @Autowired private KmsClient kmsClient;

    @BeforeAll
    void createTables() {
        enhancedClient.table("tokens", TokenItem.TABLE_SCHEMA).createTable();
        enhancedClient.table("data_keys", DataKeyItem.TABLE_SCHEMA).createTable();
    }

    @Test
    void healthEndpointReturnsUp() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotNull();
    }

    @Test
    void dynamoEnhancedClientRoundTripsTokensTable() {
        DynamoDbTable<TokenItem> table = enhancedClient.table("tokens", TokenItem.TABLE_SCHEMA);

        TokenItem item = new TokenItem();
        item.setToken("tok_scaffold_test_01");
        item.setSessionId("cs_scaffold_test_01");
        item.setCreatedAt(Instant.now().getEpochSecond());
        item.setExpiresAt(Instant.now().plusSeconds(1800).getEpochSecond());

        table.putItem(item);

        TokenItem fetched =
                table.getItem(Key.builder().partitionValue("tok_scaffold_test_01").build());

        assertThat(fetched).isNotNull();
        assertThat(fetched.getSessionId()).isEqualTo("cs_scaffold_test_01");
        assertThat(fetched.getExpiresAt()).isGreaterThan(Instant.now().getEpochSecond());
    }

    @Test
    void dynamoEnhancedClientRoundTripsDataKeysTable() {
        DynamoDbTable<DataKeyItem> table =
                enhancedClient.table("data_keys", DataKeyItem.TABLE_SCHEMA);

        DataKeyItem item = new DataKeyItem();
        item.setDataKeyId("dk_scaffold_test_01");
        item.setCreatedAt(Instant.now().getEpochSecond());

        table.putItem(item);

        DataKeyItem fetched =
                table.getItem(Key.builder().partitionValue("dk_scaffold_test_01").build());

        assertThat(fetched).isNotNull();
        assertThat(fetched.getDataKeyId()).isEqualTo("dk_scaffold_test_01");
        assertThat(fetched.getCreatedAt()).isNotNull();
    }

    @Test
    void kmsClientEncryptsAndDecryptsRoundTrip() {
        // Create a transient KMS key in LocalStack for this test — the dev alias
        // (alias/token-service-dev) doesn't exist in the Testcontainers container,
        // only in the local docker-compose LocalStack instance after bootstrap.
        CreateKeyResponse createKeyResponse =
                kmsClient.createKey(r -> r.description("token-service-scaffold-test"));
        String keyId = createKeyResponse.keyMetadata().keyId();

        byte[] plaintext = "scaffold-test-dek-bytes".getBytes(StandardCharsets.UTF_8);

        EncryptResponse encrypted =
                kmsClient.encrypt(r -> r.keyId(keyId).plaintext(SdkBytes.fromByteArray(plaintext)));

        DecryptResponse decrypted =
                kmsClient.decrypt(r -> r.keyId(keyId).ciphertextBlob(encrypted.ciphertextBlob()));

        assertThat(decrypted.plaintext().asByteArray()).isEqualTo(plaintext);
    }
}
