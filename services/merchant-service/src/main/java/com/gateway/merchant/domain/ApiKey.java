package com.gateway.merchant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "api_keys")
public class ApiKey {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "merchant_id", length = 40, nullable = false, updatable = false)
    private String merchantId;

    @Column(name = "key_prefix", length = 16, nullable = false, updatable = false)
    private String keyPrefix;

    @Column(name = "key_hash", nullable = false, updatable = false)
    private String keyHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", length = 8, nullable = false, updatable = false)
    private MerchantMode mode;

    @Column(name = "label")
    private String label;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ApiKey() {}

    public ApiKey(
            String merchantId, String keyPrefix, String keyHash, MerchantMode mode, String label) {
        this.merchantId = merchantId;
        this.keyPrefix = keyPrefix;
        this.keyHash = keyHash;
        this.mode = mode;
        this.label = label;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public MerchantMode getMode() {
        return mode;
    }

    public String getLabel() {
        return label;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
