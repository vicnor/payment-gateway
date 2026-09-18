package com.gateway.checkout.persistence;

import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

@DynamoDbBean
public class MerchantReferenceItem {
    public static final TableSchema<MerchantReferenceItem> TABLE_SCHEMA =
            TableSchema.fromBean(MerchantReferenceItem.class);
    private String referenceKey, merchantId, merchantReference, sessionId;
    private Long createdAt;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("reference_key")
    public String getReferenceKey() {
        return referenceKey;
    }

    public void setReferenceKey(String v) {
        referenceKey = v;
    }

    @DynamoDbAttribute("merchant_id")
    public String getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(String v) {
        merchantId = v;
    }

    @DynamoDbAttribute("merchant_reference")
    public String getMerchantReference() {
        return merchantReference;
    }

    public void setMerchantReference(String v) {
        merchantReference = v;
    }

    @DynamoDbAttribute("session_id")
    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String v) {
        sessionId = v;
    }

    @DynamoDbAttribute("created_at")
    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long v) {
        createdAt = v;
    }
}
