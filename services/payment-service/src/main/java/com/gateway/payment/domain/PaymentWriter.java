package com.gateway.payment.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.payment.api.mapper.PaymentMapper;
import com.gateway.payment.client.AcquireResult;
import com.gateway.payment.client.AcquirerOutcome;
import com.gateway.payment.client.DetokenizeData;
import com.gateway.payment.persistence.OutboxRepository;
import com.gateway.payment.persistence.PaymentAttemptRepository;
import com.gateway.payment.persistence.PaymentEventRepository;
import com.gateway.payment.persistence.PaymentRepository;
import com.github.f4b6a3.ulid.UlidCreator;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a payment authorization result in a single ACID transaction.
 *
 * <p>Writes four rows atomically: {@code payments}, {@code payment_attempts}, {@code
 * payment_events}, {@code outbox}. The outbox row ensures the event reaches SNS even if the process
 * crashes after the DB commit (transactional outbox pattern, publisher in task 4.3).
 *
 * <p>This class is a separate bean from {@link PaymentAuthorizationService} so that
 * {@code @Transactional} is honoured by the Spring proxy — the transaction wraps only DB I/O, not
 * the upstream HTTP calls to token-service and the acquirer.
 *
 * <p>PCI constraint: the raw PAN must not appear in any field written to the database. It is
 * discarded immediately after use in {@link PaymentAuthorizationService}.
 */
@Service
public class PaymentWriter {

    private static final Logger log = LoggerFactory.getLogger(PaymentWriter.class);

    private static final String ACQUIRER_NAME = "test-acquirer";

    private final PaymentRepository paymentRepo;
    private final PaymentAttemptRepository attemptRepo;
    private final PaymentEventRepository eventRepo;
    private final OutboxRepository outboxRepo;
    private final ObjectMapper objectMapper;

    public PaymentWriter(
            PaymentRepository paymentRepo,
            PaymentAttemptRepository attemptRepo,
            PaymentEventRepository eventRepo,
            OutboxRepository outboxRepo,
            ObjectMapper objectMapper) {
        this.paymentRepo = paymentRepo;
        this.attemptRepo = attemptRepo;
        this.eventRepo = eventRepo;
        this.outboxRepo = outboxRepo;
        this.objectMapper = objectMapper;
    }

    /**
     * Persist the outcome of a payment authorization.
     *
     * @param checkoutSessionId session that initiated the payment
     * @param merchantId merchant owning the session
     * @param merchantReference merchant's own order reference
     * @param amount amount in minor units
     * @param currency ISO 4217 currency code
     * @param token the single-use token that was detokenized
     * @param metadata merchant-supplied metadata
     * @param card card metadata from detokenize (no PAN)
     * @param result acquirer authorization result
     * @param durationMs time taken for the acquirer call
     * @return the saved {@link Payment} entity (in terminal state)
     */
    @Transactional
    public Payment persist(
            String checkoutSessionId,
            String merchantId,
            String merchantReference,
            long amount,
            String currency,
            String token,
            Map<String, Object> metadata,
            DetokenizeData card,
            AcquireResult result,
            long durationMs) {

        boolean captured = result.outcome() == AcquirerOutcome.APPROVED;
        PaymentStatus terminalStatus = captured ? PaymentStatus.CAPTURED : PaymentStatus.FAILED;

        PaymentMethodDetails pmd =
                new PaymentMethodDetails(
                        card.brand(),
                        card.last4(),
                        card.expMonth(),
                        card.expYear(),
                        card.country());

        Payment payment =
                new Payment(
                        UUID.randomUUID(),
                        "pay_" + UlidCreator.getUlid(),
                        checkoutSessionId,
                        merchantId,
                        merchantReference,
                        amount,
                        currency,
                        terminalStatus,
                        pmd,
                        metadata);

        Instant now = Instant.now();
        if (captured) {
            payment.setAmountCaptured(amount);
            payment.setAcquirer(ACQUIRER_NAME);
            payment.setAcquirerReference(result.acquirerReference());
            payment.setAuthCode(result.authCode());
            payment.setAuthorizedAt(now);
            payment.setCapturedAt(now);
        } else {
            payment.setFailureCode(result.errorCode());
            payment.setFailureMessage(describeFailure(result.errorCode()));
        }

        paymentRepo.save(payment);
        log.info(
                "payment persisted: external_id={} status={} merchant_id={}",
                payment.getExternalId(),
                payment.getStatus(),
                merchantId);

        // payment_attempts — token + amount only, never the PAN
        Map<String, Object> requestPayload =
                Map.of(
                        "token", token,
                        "amount", amount,
                        "currency", currency,
                        "acquirer", ACQUIRER_NAME);
        Map<String, Object> responsePayload = buildResponsePayload(result);

        attemptRepo.save(
                new PaymentAttempt(
                        UUID.randomUUID(),
                        payment.getId(),
                        1,
                        ACQUIRER_NAME,
                        requestPayload,
                        responsePayload,
                        result.outcome().name(),
                        (int) Math.min(durationMs, Integer.MAX_VALUE)));

        // payment_events — state transition audit
        eventRepo.save(
                new PaymentEvent(
                        payment.getId(),
                        terminalStatus == PaymentStatus.CAPTURED
                                ? "payment.captured"
                                : "payment.failed",
                        PaymentStatus.PENDING.name(),
                        terminalStatus.name(),
                        Map.of()));

        // outbox — full event envelope for the SNS publisher (task 4.3)
        String eventType =
                terminalStatus == PaymentStatus.CAPTURED ? "payment.captured" : "payment.failed";
        outboxRepo.save(
                new OutboxRecord(
                        payment.getExternalId(),
                        eventType,
                        buildEventEnvelope(eventType, payment)));

        return payment;
    }

    private Map<String, Object> buildResponsePayload(AcquireResult result) {
        if (result.outcome() == AcquirerOutcome.APPROVED) {
            return Map.of(
                    "outcome", result.outcome().name(),
                    "auth_code", result.authCode(),
                    "acquirer_reference", result.acquirerReference());
        }
        return Map.of(
                "outcome",
                result.outcome().name(),
                "error_code",
                result.errorCode() != null ? result.errorCode() : "unknown");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildEventEnvelope(String eventType, Payment payment) {
        String eventId = "evt_" + UlidCreator.getUlid();
        // Convert the payment response DTO to a map so the publisher can forward it verbatim
        Map<String, Object> paymentData =
                objectMapper.convertValue(PaymentMapper.toResponse(payment), Map.class);
        return Map.of(
                "id",
                eventId,
                "type",
                eventType,
                "created",
                Instant.now().getEpochSecond(),
                "livemode",
                false,
                "data",
                Map.of("object", paymentData));
    }

    private static String describeFailure(String code) {
        if (code == null) return "Payment failed.";
        return switch (code) {
            case "card_declined" -> "Your card was declined.";
            case "insufficient_funds" -> "Insufficient funds.";
            case "processing_error" -> "A processing error occurred. Please try again.";
            default -> "Payment failed: " + code;
        };
    }
}
