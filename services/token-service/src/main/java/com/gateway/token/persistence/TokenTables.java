package com.gateway.token.persistence;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

/**
 * Provides {@link DynamoDbTable} beans for the token-service DynamoDB tables.
 *
 * <p>Using the application's own {@link TokenItem#TABLE_SCHEMA} and {@link
 * DataKeyItem#TABLE_SCHEMA} as the single source of truth for key mapping — integration tests call
 * {@code table.createTable()} with the same schemas so the live table structure is always
 * consistent with what the app code expects.
 */
@Configuration
public class TokenTables {

    private final DynamoDbEnhancedClient enhancedClient;

    public TokenTables(DynamoDbEnhancedClient enhancedClient) {
        this.enhancedClient = enhancedClient;
    }

    @Bean
    public DynamoDbTable<TokenItem> tokensTable() {
        return enhancedClient.table("tokens", TokenItem.TABLE_SCHEMA);
    }

    @Bean
    public DynamoDbTable<DataKeyItem> dataKeysTable() {
        return enhancedClient.table("data_keys", DataKeyItem.TABLE_SCHEMA);
    }
}
