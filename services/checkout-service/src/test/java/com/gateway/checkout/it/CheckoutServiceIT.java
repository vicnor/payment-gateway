package com.gateway.checkout.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.checkout.CheckoutServiceApplication;
import com.gateway.checkout.persistence.CheckoutSessionItem;
import com.gateway.checkout.persistence.IdempotencyKeyItem;
import com.gateway.shared.testing.AbstractDynamoIT;
import java.time.Instant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndexDescription;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;

@TestInstance(Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = CheckoutServiceApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT)
class CheckoutServiceIT extends AbstractDynamoIT {

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private DynamoDbClient dynamoDbClient;

    @Autowired
    @Qualifier("checkoutSessionsTable")
    private DynamoDbTable<CheckoutSessionItem> checkoutSessionsTable;

    @Autowired
    @Qualifier("checkoutIdempotencyKeysTable")
    private DynamoDbTable<IdempotencyKeyItem> checkoutIdempotencyKeysTable;

    @BeforeAll
    void createTables() {
        createTableIfAbsent(checkoutSessionsTable);
        createTableIfAbsent(checkoutIdempotencyKeysTable);
    }

    private void createTableIfAbsent(DynamoDbTable<?> table) {
        try {
            table.createTable();
        } catch (ResourceInUseException ignored) {
            // The shared test container may already hold a table created by another test class.
        }
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
    void checkoutSessionsTableRoundTripsAndDefinesMerchantCreatedIndex() {
        long now = Instant.now().getEpochSecond();
        CheckoutSessionItem item = new CheckoutSessionItem();
        item.setSessionId("cs_scaffold_test_01");
        item.setMerchantId("mer_scaffold_test_01");
        item.setCreatedAt(now);
        item.setExpiresAt(now + 1800);

        checkoutSessionsTable.putItem(item);

        CheckoutSessionItem fetched =
                checkoutSessionsTable.getItem(
                        Key.builder().partitionValue("cs_scaffold_test_01").build());
        assertThat(fetched).isNotNull();
        assertThat(fetched.getMerchantId()).isEqualTo("mer_scaffold_test_01");
        assertThat(fetched.getCreatedAt()).isEqualTo(now);
        assertThat(fetched.getExpiresAt()).isEqualTo(now + 1800);

        GlobalSecondaryIndexDescription index =
                dynamoDbClient
                        .describeTable(r -> r.tableName("checkout_sessions"))
                        .table()
                        .globalSecondaryIndexes()
                        .stream()
                        .filter(
                                candidate ->
                                        CheckoutSessionItem.MERCHANT_CREATED_INDEX.equals(
                                                candidate.indexName()))
                        .findFirst()
                        .orElseThrow();
        assertThat(index.keySchema())
                .extracting(key -> key.attributeName())
                .containsExactly("merchant_id", "created_at");
    }

    @Test
    void checkoutIdempotencyKeysTableRoundTrips() {
        long expiresAt = Instant.now().plusSeconds(86_400).getEpochSecond();
        IdempotencyKeyItem item = new IdempotencyKeyItem();
        item.setIdempotencyKey("mer_scaffold_test_01#550e8400-e29b-41d4-a716-446655440000");
        item.setExpiresAt(expiresAt);

        checkoutIdempotencyKeysTable.putItem(item);

        IdempotencyKeyItem fetched =
                checkoutIdempotencyKeysTable.getItem(
                        Key.builder()
                                .partitionValue(
                                        "mer_scaffold_test_01#550e8400-e29b-41d4-a716-446655440000")
                                .build());
        assertThat(fetched).isNotNull();
        assertThat(fetched.getExpiresAt()).isEqualTo(expiresAt);
    }
}
