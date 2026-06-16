package com.gateway.token.persistence;

import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

/**
 * DynamoDB item mapping for the {@code tokens} table.
 *
 * <p>Attribute names use snake_case to match the DynamoDB table schema defined in {@code
 * infrastructure/scripts/bootstrap-aws.sh} and {@code docs/architecture/data-model.md}.
 *
 * <p>PCI constraint: {@code encrypted_card_data} contains AES-256-GCM ciphertext (IV + tag
 * included). The raw PAN and CVV are never stored here — only the ciphertext and a reference to the
 * encrypted DEK in the {@code data_keys} table.
 */
@DynamoDbBean
public class TokenItem {

    /** Reusable schema instance — shared by the app config and integration tests. */
    public static final TableSchema<TokenItem> TABLE_SCHEMA = TableSchema.fromBean(TokenItem.class);

    private String token;
    private String sessionId;
    private String merchantId;
    private String encryptedCardData;
    private String dataKeyId;
    private CardAttribute card;
    private Boolean singleUse;
    private Boolean used;
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

    @DynamoDbAttribute("merchant_id")
    public String getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(String merchantId) {
        this.merchantId = merchantId;
    }

    @DynamoDbAttribute("encrypted_card_data")
    public String getEncryptedCardData() {
        return encryptedCardData;
    }

    public void setEncryptedCardData(String encryptedCardData) {
        this.encryptedCardData = encryptedCardData;
    }

    @DynamoDbAttribute("data_key_id")
    public String getDataKeyId() {
        return dataKeyId;
    }

    public void setDataKeyId(String dataKeyId) {
        this.dataKeyId = dataKeyId;
    }

    @DynamoDbAttribute("card")
    public CardAttribute getCard() {
        return card;
    }

    public void setCard(CardAttribute card) {
        this.card = card;
    }

    @DynamoDbAttribute("single_use")
    public Boolean getSingleUse() {
        return singleUse;
    }

    public void setSingleUse(Boolean singleUse) {
        this.singleUse = singleUse;
    }

    @DynamoDbAttribute("used")
    public Boolean getUsed() {
        return used;
    }

    public void setUsed(Boolean used) {
        this.used = used;
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
