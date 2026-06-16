package com.gateway.token.persistence;

import java.util.Optional;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.UpdateItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

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

    public Optional<TokenItem> findById(String tokenId) {
        return Optional.ofNullable(table.getItem(Key.builder().partitionValue(tokenId).build()));
    }

    /**
     * Atomically marks the token as used via a conditional update.
     *
     * <p>The condition {@code used = false} ensures only one caller can ever detokenize a given
     * token. If the condition fails (token already used), DynamoDB throws {@link
     * software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException}.
     */
    public void markUsed(String tokenId) {
        TokenItem update = new TokenItem();
        update.setToken(tokenId);
        update.setUsed(true);

        Expression condition =
                Expression.builder()
                        .expression("used = :notUsed")
                        .putExpressionValue(
                                ":notUsed", AttributeValue.builder().bool(false).build())
                        .build();

        table.updateItem(
                UpdateItemEnhancedRequest.builder(TokenItem.class)
                        .item(update)
                        .conditionExpression(condition)
                        .ignoreNulls(true)
                        .build());
    }
}
