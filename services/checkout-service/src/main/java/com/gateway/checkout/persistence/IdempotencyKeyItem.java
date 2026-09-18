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
    private String requestHash;
    private String ownerToken;
    private String status;
    private Long leaseUntil;
    private Integer responseStatus;
    private String responseContentType;
    private String encryptedSecret;
    private String sessionId;
    private String responseBody;
    private Long expiresAt;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("idempotency_key")
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    @DynamoDbAttribute("request_hash")
    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String v) {
        requestHash = v;
    }

    @DynamoDbAttribute("owner_token")
    public String getOwnerToken() {
        return ownerToken;
    }

    public void setOwnerToken(String v) {
        ownerToken = v;
    }

    @DynamoDbAttribute("status")
    public String getStatus() {
        return status;
    }

    public void setStatus(String v) {
        status = v;
    }

    @DynamoDbAttribute("lease_until")
    public Long getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(Long v) {
        leaseUntil = v;
    }

    @DynamoDbAttribute("response_status")
    public Integer getResponseStatus() {
        return responseStatus;
    }

    public void setResponseStatus(Integer v) {
        responseStatus = v;
    }

    @DynamoDbAttribute("response_content_type")
    public String getResponseContentType() {
        return responseContentType;
    }

    public void setResponseContentType(String v) {
        responseContentType = v;
    }

    @DynamoDbAttribute("encrypted_secret")
    public String getEncryptedSecret() {
        return encryptedSecret;
    }

    public void setEncryptedSecret(String v) {
        encryptedSecret = v;
    }

    @DynamoDbAttribute("session_id")
    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String v) {
        sessionId = v;
    }

    @DynamoDbAttribute("response_body")
    public String getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(String v) {
        responseBody = v;
    }

    @DynamoDbAttribute("expires_at")
    public Long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Long expiresAt) {
        this.expiresAt = expiresAt;
    }
}
