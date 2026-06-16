package com.gateway.token.persistence;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

/** Thin persistence adapter for the {@code tokens} DynamoDB table. */
@Component
public class TokenStore {

    private final DynamoDbTable<TokenItem> table;

    public TokenStore(DynamoDbTable<TokenItem> tokensTable) {
        this.table = tokensTable;
    }

    public void save(TokenItem item) {
        table.putItem(item);
    }
}
