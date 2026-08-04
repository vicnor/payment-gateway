package com.gateway.payment.domain;

import com.gateway.payment.api.dto.CreatePaymentRequest;
import com.gateway.payment.api.dto.PaymentResponse;
import com.gateway.payment.api.mapper.PaymentMapper;
import com.gateway.payment.client.AcquireResult;
import com.gateway.payment.client.AcquirerClient;
import com.gateway.payment.client.DetokenizeData;
import com.gateway.payment.client.TokenServiceClient;
import com.github.f4b6a3.ulid.UlidCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the synchronous payment authorization flow.
 *
 * <p>Sequence:
 *
 * <ol>
 *   <li>Detokenize the single-use token (token-service) — propagates 404/409 on failure; no DB row
 *       is written.
 *   <li>Call the acquirer (test-acquirer-service) and time the call — propagates 503 {@link
 *       com.gateway.shared.web.error.AcquirerUnavailableException} on timeout; no DB row is
 *       written.
 *   <li>Delegate to {@link PaymentWriter} which persists {@code payments}, {@code
 *       payment_attempts}, {@code payment_events}, and {@code outbox} in a single transaction.
 * </ol>
 *
 * <p>The DB transaction is deliberately NOT held open during the HTTP calls to token-service and
 * the acquirer — that is why the orchestration and the persistence are in separate beans.
 *
 * <p>PCI: the raw PAN is held only in a local variable for the duration of this method and is never
 * passed to {@link PaymentWriter} or logged.
 */
@Service
public class PaymentAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentAuthorizationService.class);

    private final TokenServiceClient tokenClient;
    private final AcquirerClient acquirerClient;
    private final PaymentWriter writer;

    public PaymentAuthorizationService(
            TokenServiceClient tokenClient, AcquirerClient acquirerClient, PaymentWriter writer) {
        this.tokenClient = tokenClient;
        this.acquirerClient = acquirerClient;
        this.writer = writer;
    }

    /**
     * Authorize a payment.
     *
     * @throws com.gateway.shared.web.error.NotFoundException token expired / not found (404)
     * @throws com.gateway.shared.web.error.ConflictException token already used (409)
     * @throws com.gateway.shared.web.error.AcquirerUnavailableException acquirer timeout / down
     *     (503)
     */
    public PaymentResponse charge(CreatePaymentRequest request) {
        // Step 1 — detokenize (no DB transaction open)
        DetokenizeData card = tokenClient.detokenize(request.token());

        // Step 2 — call acquirer and measure duration (no DB transaction open)
        String acquirerReference = "pay_" + UlidCreator.getUlid();
        long startMs = System.currentTimeMillis();
        AcquireResult result =
                acquirerClient.authorize(
                        card.pan(),
                        card.expMonth(),
                        card.expYear(),
                        null, // CVV not available in v1 single-use-token flow — see ADR-0001
                        request.amount(),
                        request.currency(),
                        acquirerReference);
        long durationMs = System.currentTimeMillis() - startMs;

        log.info(
                "acquirer response: outcome={} reference={} duration_ms={}",
                result.outcome(),
                acquirerReference,
                durationMs);

        // Step 3 — persist in one transaction (PAN goes out of scope here)
        Payment payment =
                writer.persist(
                        request.checkoutSessionId(),
                        request.merchantId(),
                        request.merchantReference(),
                        request.amount(),
                        request.currency(),
                        request.token(),
                        request.metadata(),
                        card,
                        result,
                        durationMs);

        return PaymentMapper.toResponse(payment);
    }
}
