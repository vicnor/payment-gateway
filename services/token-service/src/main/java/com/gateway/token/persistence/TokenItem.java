package com.gateway.token.persistence;

import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

/**
 * DynamoDB item mapping for the {@code tokens} table.
 *
 * <p>Scaffold: carries only the fields needed to prove the enhanced-client round-trip. Full card
 * data fields ({@code encrypted_card_data}, {@code data_key_id}, {@code card}, {@code used}, {@code
 * single_use}, {@code merchant_id}) are added in task 2.2 when the tokenize endpoint lands.
 *
 * <p>Attribute names use snake_case to match the DynamoDB table schema defined in {@code
 * infrastructure/scripts/bootstrap-aws.sh} and {@code docs/architecture/data-model.md}.
 */
@DynamoDbBean
public class TokenItem {

    /** Reusable schema instance — shared by the app config and integration tests. */
    public static final TableSchema<TokenItem> TABLE_SCHEMA = TableSchema.fromBean(TokenItem.class);

    private String token;
    private String sessionId;
    private Long createdAt;
    private Long expiresAt;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("token")
    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    @DynamoDbAttribute("session_id")
    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    @DynamoDbAttribute("created_at")
    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    @DynamoDbAttribute("expires_at")
    public Long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Long expiresAt) {
        this.expiresAt = expiresAt;
    }
}
