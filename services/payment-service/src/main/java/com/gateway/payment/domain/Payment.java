package com.gateway.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The {@code Payment} aggregate root.
 *
 * <p>Mapped to the {@code payments} table (see {@code V001__init.sql}). The {@code external_id}
 * ({@code pay_<ULID>}) is the public-facing identifier; the {@code id} UUID is the internal PK.
 *
 * <p>v1 state machine: {@code PENDING → CAPTURED | FAILED} in a single "sale" (no separate capture
 * step). {@code AUTHORIZED} is reserved for future split auth/capture.
 *
 * <p>PCI constraint: the raw PAN is never stored here. Card metadata (brand, last4, expiry,
 * country) comes from the token record via detokenize and is stored in {@code
 * payment_method_details} JSONB.
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "external_id", length = 40, nullable = false, unique = true)
    private String externalId;

    @Column(name = "checkout_session_id", length = 40, nullable = false)
    private String checkoutSessionId;

    @Column(name = "merchant_id", length = 40, nullable = false)
    private String merchantId;

    @Column(name = "merchant_reference", length = 255, nullable = false)
    private String merchantReference;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "amount_captured", nullable = false)
    private Long amountCaptured;

    @Column(name = "amount_refunded", nullable = false)
    private Long amountRefunded;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "status", length = 32, nullable = false)
    private String status;

    @Column(name = "payment_method", length = 32, nullable = false)
    private String paymentMethod;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payment_method_details", nullable = false, columnDefinition = "jsonb")
    private PaymentMethodDetails paymentMethodDetails;

    @Column(name = "acquirer", length = 64)
    private String acquirer;

    @Column(name = "acquirer_reference", length = 255)
    private String acquirerReference;

    @Column(name = "auth_code", length = 32)
    private String authCode;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_message")
    private String failureMessage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "authorized_at")
    private Instant authorizedAt;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    protected Payment() {}

    public Payment(
            UUID id,
            String externalId,
            String checkoutSessionId,
            String merchantId,
            String merchantReference,
            Long amount,
            String currency,
            PaymentStatus status,
            PaymentMethodDetails paymentMethodDetails,
            Map<String, Object> metadata) {
        this.id = id;
        this.externalId = externalId;
        this.checkoutSessionId = checkoutSessionId;
        this.merchantId = merchantId;
        this.merchantReference = merchantReference;
        this.amount = amount;
        this.amountCaptured = 0L;
        this.amountRefunded = 0L;
        this.currency = currency;
        this.status = status.name();
        this.paymentMethod = "card";
        this.paymentMethodDetails = paymentMethodDetails;
        this.metadata = metadata != null ? metadata : Map.of();
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getCheckoutSessionId() {
        return checkoutSessionId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getMerchantReference() {
        return merchantReference;
    }

    public Long getAmount() {
        return amount;
    }

    public Long getAmountCaptured() {
        return amountCaptured;
    }

    public Long getAmountRefunded() {
        return amountRefunded;
    }

    public String getCurrency() {
        return currency;
    }

    public String getStatus() {
        return status;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public PaymentMethodDetails getPaymentMethodDetails() {
        return paymentMethodDetails;
    }

    public String getAcquirer() {
        return acquirer;
    }

    public String getAcquirerReference() {
        return acquirerReference;
    }

    public String getAuthCode() {
        return authCode;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getAuthorizedAt() {
        return authorizedAt;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    // -------------------------------------------------------------------------
    // Setters (only for mutable state set post-construction by the writer)
    // -------------------------------------------------------------------------

    public void setAmountCaptured(Long amountCaptured) {
        this.amountCaptured = amountCaptured;
    }

    public void setAcquirer(String acquirer) {
        this.acquirer = acquirer;
    }

    public void setAcquirerReference(String acquirerReference) {
        this.acquirerReference = acquirerReference;
    }

    public void setAuthCode(String authCode) {
        this.authCode = authCode;
    }

    public void setFailureCode(String failureCode) {
        this.failureCode = failureCode;
    }

    public void setFailureMessage(String failureMessage) {
        this.failureMessage = failureMessage;
    }

    public void setAuthorizedAt(Instant authorizedAt) {
        this.authorizedAt = authorizedAt;
    }

    public void setCapturedAt(Instant capturedAt) {
        this.capturedAt = capturedAt;
    }
}
