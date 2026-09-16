package com.gateway.checkout.persistence;

import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

@DynamoDbBean
public class IdempotencyKeyItem {

    public static final TableSchema<IdempotencyKeyItem> TABLE_SCHEMA =
            TableSchema.fromBean(IdempotencyKeyItem.class);

    private String idempotencyKey;
    private Long expiresAt;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("idempotency_key")
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    @DynamoDbAttribute("expires_at")
    public Long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Long expiresAt) {
        this.expiresAt = expiresAt;
    }
}
