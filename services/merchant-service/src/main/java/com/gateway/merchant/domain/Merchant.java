package com.gateway.merchant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "merchants")
public class Merchant {

    @Id
    @Column(name = "id", length = 40)
    private String id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "callback_url", nullable = false)
    private String callbackUrl;

    @Column(name = "return_url_pattern", nullable = false)
    private String returnUrlPattern;

    @Column(name = "cancel_url_pattern", nullable = false)
    private String cancelUrlPattern;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "branding", nullable = false, columnDefinition = "jsonb")
    private Branding branding;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", length = 8, nullable = false)
    private MerchantMode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private MerchantStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Merchant() {}

    public Merchant(
            String id,
            String name,
            String callbackUrl,
            String returnUrlPattern,
            String cancelUrlPattern,
            Branding branding,
            MerchantMode mode) {
        this.id = id;
        this.name = name;
        this.callbackUrl = callbackUrl;
        this.returnUrlPattern = returnUrlPattern;
        this.cancelUrlPattern = cancelUrlPattern;
        this.branding = branding != null ? branding : Branding.empty();
        this.mode = mode;
        this.status = MerchantStatus.ACTIVE;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCallbackUrl() {
        return callbackUrl;
    }

    public String getReturnUrlPattern() {
        return returnUrlPattern;
    }

    public String getCancelUrlPattern() {
        return cancelUrlPattern;
    }

    public Branding getBranding() {
        return branding;
    }

    public MerchantMode getMode() {
        return mode;
    }

    public MerchantStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setStatus(MerchantStatus status) {
        this.status = status;
    }
}
