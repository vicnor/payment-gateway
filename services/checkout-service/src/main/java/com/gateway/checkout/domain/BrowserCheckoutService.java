package com.gateway.checkout.domain;

import com.gateway.checkout.api.CompletionInProgressException;
import com.gateway.checkout.api.dto.CancelCheckoutResponse;
import com.gateway.checkout.api.dto.CompleteCheckoutResponse;
import com.gateway.checkout.api.dto.SessionDetailsResponse;
import com.gateway.checkout.client.PaymentClient;
import com.gateway.checkout.persistence.BrandingAttribute;
import com.gateway.checkout.persistence.CheckoutSessionItem;
import com.gateway.shared.web.error.ConflictException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

@Service
public class BrowserCheckoutService {
    private final com.gateway.checkout.persistence.CheckoutTransitions transitions;
    private final DynamoDbClient dynamo;
    private final DynamoDbTable<CheckoutSessionItem> sessions;
    private final CheckoutSessionAuthenticator authenticator;
    private final PaymentClient payments;
    private final Clock clock;

    public BrowserCheckoutService(
            DynamoDbClient dynamo,
            @Qualifier("checkoutSessionsTable") DynamoDbTable<CheckoutSessionItem> sessions,
            CheckoutSessionAuthenticator authenticator,
            PaymentClient payments,
            Clock clock,
            com.gateway.checkout.persistence.CheckoutTransitions transitions) {
        this.transitions = transitions;
        this.dynamo = dynamo;
        this.sessions = sessions;
        this.authenticator = authenticator;
        this.payments = payments;
        this.clock = clock;
    }

    public SessionDetailsResponse details(String id, String secret) {
        CheckoutSessionItem item = authenticator.authenticate(id, secret);
        if (CheckoutSessionStatus.CREATED.name().equals(item.getStatus())) {
            long now = now();
            try {
                dynamo.updateItem(
                        UpdateItemRequest.builder()
                                .tableName(sessions.tableName())
                                .key(key(id))
                                .conditionExpression("#s=:created AND expires_at>:now")
                                .updateExpression("SET #s=:progress, updated_at=:now")
                                .expressionAttributeNames(Map.of("#s", "status"))
                                .expressionAttributeValues(
                                        Map.of(
                                                ":created",
                                                s("CREATED"),
                                                ":progress",
                                                s("IN_PROGRESS"),
                                                ":now",
                                                n(now)))
                                .build());
                item = get(id);
            } catch (ConditionalCheckFailedException ignored) {
                item = get(id);
            }
        }
        BrandingAttribute branding = item.getBranding();
        return new SessionDetailsResponse(
                item.getSessionId(),
                item.getStatus(),
                item.getAmount(),
                item.getCurrency(),
                item.getDescription(),
                new SessionDetailsResponse.Merchant(
                        item.getMerchantName(),
                        branding == null ? null : branding.getLogoUrl(),
                        branding == null ? null : branding.getAccentColor()),
                item.getAvailablePaymentMethods(),
                item.getLocale(),
                item.getExpiresAt());
    }

    public CompleteCheckoutResponse complete(String id, String secret, String token) {
        CheckoutSessionItem item = authenticator.authenticate(id, secret);
        String tokenHash = sha256(token);
        String attemptId = "ca_" + sha256(id + "\0" + token);
        CompleteCheckoutResponse replay = replay(item, tokenHash);
        if (replay != null) return replay;
        if (CheckoutSessionStatus.COMPLETED.name().equals(item.getStatus())) return completed(item);
        if (!CheckoutSessionStatus.CREATED.name().equals(item.getStatus())
                && !CheckoutSessionStatus.IN_PROGRESS.name().equals(item.getStatus())) {
            throw new ConflictException(
                    "invalid_checkout_session_state", "Checkout session cannot be completed.");
        }

        long now = now();
        try {
            transitions.claim(id, attemptId, tokenHash);
        } catch (ConditionalCheckFailedException race) {
            item = authenticator.authenticate(id, secret);
            replay = replay(item, tokenHash);
            if (replay != null) return replay;
            if (CheckoutSessionStatus.COMPLETED.name().equals(item.getStatus()))
                return completed(item);
            throw new CompletionInProgressException(
                    "completion_in_progress", "A checkout completion is already in progress.");
        }

        item.setActiveCompletionAttemptId(attemptId);
        item.setActiveCompletionTokenHash(tokenHash);
        try {
            PaymentClient.PaymentResult payment =
                    payments.create(
                            new PaymentClient.CreatePayment(
                                    attemptId,
                                    id,
                                    item.getMerchantId(),
                                    item.getAmount(),
                                    item.getCurrency(),
                                    item.getMerchantReference(),
                                    token,
                                    item.getMetadata()));
            if ("CAPTURED".equals(payment.status())) {
                transitions.resolve(item, payment);
                return new CompleteCheckoutResponse(
                        "COMPLETED", payment.id(), null, null, null, successUrl(item));
            }
            transitions.resolve(item, payment);
            return new CompleteCheckoutResponse(
                    "FAILED",
                    payment.id(),
                    payment.failureCode(),
                    payment.failureMessage(),
                    true,
                    null);
        } catch (com.gateway.shared.web.error.ValidationException ex) {
            transitions.release(id, attemptId);
            throw ex;
        } catch (ConflictException ex) {
            if ("token_session_mismatch".equals(ex.code())) transitions.release(id, attemptId);
            // A used token may belong to a previously authorized but not yet persisted payment.
            throw ex;
        }
    }

    public CancelCheckoutResponse cancel(String id, String secret) {
        CheckoutSessionItem item = authenticator.authenticate(id, secret);
        if (CheckoutSessionStatus.CANCELLED.name().equals(item.getStatus()))
            return new CancelCheckoutResponse(item.getCancelUrl());
        if (!transitions.cancel(item)) {
            CheckoutSessionItem current = authenticator.authenticate(id, secret);
            if (CheckoutSessionStatus.CANCELLED.name().equals(current.getStatus()))
                return new CancelCheckoutResponse(current.getCancelUrl());
            if (current.getActiveCompletionAttemptId() != null)
                throw new CompletionInProgressException(
                        "checkout_completion_in_progress", "Payment authorization is in progress.");
            throw new ConflictException(
                    "invalid_checkout_session_state", "Checkout session cannot be cancelled.");
        }
        return new CancelCheckoutResponse(item.getCancelUrl());
    }

    private CompleteCheckoutResponse replay(CheckoutSessionItem item, String tokenHash) {
        if (!tokenHash.equals(item.getLastCompletionTokenHash())) return null;
        if ("COMPLETED".equals(item.getLastCompletionStatus())) return completed(item);
        if ("FAILED".equals(item.getLastCompletionStatus()))
            return new CompleteCheckoutResponse(
                    "FAILED",
                    item.getLastCompletionPaymentId(),
                    item.getLastFailureCode(),
                    item.getLastFailureMessage(),
                    Boolean.TRUE.equals(item.getLastRetryAllowed()),
                    null);
        return null;
    }

    private CompleteCheckoutResponse completed(CheckoutSessionItem item) {
        return new CompleteCheckoutResponse(
                "COMPLETED", item.getPaymentId(), null, null, null, successUrl(item));
    }

    private static String successUrl(CheckoutSessionItem item) {
        return UriComponentsBuilder.fromUriString(item.getReturnUrl())
                .queryParam("checkout", item.getSessionId())
                .build(true)
                .toUriString();
    }

    private CheckoutSessionItem get(String id) {
        return sessions.getItem(Key.builder().partitionValue(id).build());
    }

    private long now() {
        return clock.instant().getEpochSecond();
    }

    private static Map<String, AttributeValue> key(String id) {
        return Map.of("session_id", s(id));
    }

    private static AttributeValue s(String v) {
        return AttributeValue.fromS(v);
    }

    private static AttributeValue n(long v) {
        return AttributeValue.fromN(Long.toString(v));
    }

    private static String sha256(String v) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(v.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
