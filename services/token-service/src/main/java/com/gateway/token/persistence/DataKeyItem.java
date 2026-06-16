package com.gateway.token.persistence;

import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

/**
 * DynamoDB item mapping for the {@code data_keys} table.
 *
 * <p>Stores per-token envelope encryption keys (DEKs) after KMS-wrapping. The plain DEK is never
 * persisted — only the ciphertext produced by {@code KMS.encrypt(CMK, plainDek)}.
 *
 * <p>Partition key name is {@code data_key_id} — the canonical name from {@code
 * docs/architecture/data-model.md}. This matches the DynamoDB table created by {@code
 * infrastructure/scripts/bootstrap-aws.sh}.
 */
@DynamoDbBean
public class DataKeyItem {

    /** Reusable schema instance — shared by the app config and integration tests. */
    public static final TableSchema<DataKeyItem> TABLE_SCHEMA =
            TableSchema.fromBean(DataKeyItem.class);

    private String dataKeyId;
    private String encryptedDek;
    private String kmsKeyId;
    private Long createdAt;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("data_key_id")
    public String getDataKeyId() {
        return dataKeyId;
    }

    public void setDataKeyId(String dataKeyId) {
        this.dataKeyId = dataKeyId;
    }

    @DynamoDbAttribute("encrypted_dek")
    public String getEncryptedDek() {
        return encryptedDek;
    }

    public void setEncryptedDek(String encryptedDek) {
        this.encryptedDek = encryptedDek;
    }

    @DynamoDbAttribute("kms_key_id")
    public String getKmsKeyId() {
        return kmsKeyId;
    }

    public void setKmsKeyId(String kmsKeyId) {
        this.kmsKeyId = kmsKeyId;
    }

    @DynamoDbAttribute("created_at")
    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }
}
