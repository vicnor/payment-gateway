package com.gateway.checkout.persistence;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

@Configuration
public class CheckoutTables {

    private final DynamoDbEnhancedClient enhancedClient;

    public CheckoutTables(DynamoDbEnhancedClient enhancedClient) {
        this.enhancedClient = enhancedClient;
    }

    @Bean
    public DynamoDbTable<CheckoutSessionItem> checkoutSessionsTable() {
        return enhancedClient.table("checkout_sessions", CheckoutSessionItem.TABLE_SCHEMA);
    }

    @Bean
    public DynamoDbTable<IdempotencyKeyItem> checkoutIdempotencyKeysTable() {
        return enhancedClient.table("checkout_idempotency_keys", IdempotencyKeyItem.TABLE_SCHEMA);
    }
}
