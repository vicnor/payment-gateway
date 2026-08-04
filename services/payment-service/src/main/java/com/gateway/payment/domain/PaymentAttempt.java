package com.gateway.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A single attempt to authorize a payment against an acquirer.
 *
 * <p>Mapped to the {@code payment_attempts} table. {@code requestPayload} stores the token and
 * amount — <strong>never the raw PAN</strong>. {@code responsePayload} stores the acquirer outcome.
 */
@Entity
@Table(name = "payment_attempts")
public class PaymentAttempt {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;

    @Column(name = "acquirer", length = 64, nullable = false)
    private String acquirer;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> requestPayload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_payload", columnDefinition = "jsonb")
    private Map<String, Object> responsePayload;

    @Column(name = "acquirer_status", length = 64)
    private String acquirerStatus;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PaymentAttempt() {}

    public PaymentAttempt(
            UUID id,
            UUID paymentId,
            int attemptNumber,
            String acquirer,
            Map<String, Object> requestPayload,
            Map<String, Object> responsePayload,
            String acquirerStatus,
            int durationMs) {
        this.id = id;
        this.paymentId = paymentId;
        this.attemptNumber = attemptNumber;
        this.acquirer = acquirer;
        this.requestPayload = requestPayload;
        this.responsePayload = responsePayload;
        this.acquirerStatus = acquirerStatus;
        this.durationMs = durationMs;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public Integer getAttemptNumber() {
        return attemptNumber;
    }

    public String getAcquirer() {
        return acquirer;
    }

    public Map<String, Object> getRequestPayload() {
        return requestPayload;
    }

    public Map<String, Object> getResponsePayload() {
        return responsePayload;
    }

    public String getAcquirerStatus() {
        return acquirerStatus;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
