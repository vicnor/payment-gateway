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
    private static final long COMPLETION_LEASE_SECONDS = 60;
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
            Clock clock) {
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
            dynamo.updateItem(
                    UpdateItemRequest.builder()
                            .tableName(sessions.tableName())
                            .key(key(id))
                            .conditionExpression(
                                    "(#s=:created OR #s=:progress) AND expires_at>:now AND "
                                            + "(attribute_not_exists(active_completion_attempt_id) OR completion_lease_until<:now)")
                            .updateExpression(
                                    "SET #s=:progress, active_completion_attempt_id=:attempt, "
                                            + "active_completion_token_hash=:tokenHash, completion_lease_until=:lease, updated_at=:now")
                            .expressionAttributeNames(Map.of("#s", "status"))
                            .expressionAttributeValues(
                                    Map.of(
                                            ":created",
                                            s("CREATED"),
                                            ":progress",
                                            s("IN_PROGRESS"),
                                            ":now",
                                            n(now),
                                            ":attempt",
                                            s(attemptId),
                                            ":tokenHash",
                                            s(tokenHash),
                                            ":lease",
                                            n(now + COMPLETION_LEASE_SECONDS)))
                            .build());
        } catch (ConditionalCheckFailedException race) {
            item = authenticator.authenticate(id, secret);
            replay = replay(item, tokenHash);
            if (replay != null) return replay;
            if (CheckoutSessionStatus.COMPLETED.name().equals(item.getStatus()))
                return completed(item);
            throw new CompletionInProgressException(
                    "completion_in_progress", "A checkout completion is already in progress.");
        }

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
                finishCaptured(id, attemptId, tokenHash, payment.id());
                return new CompleteCheckoutResponse(
                        "COMPLETED", payment.id(), null, null, null, successUrl(item));
            }
            finishFailed(id, attemptId, tokenHash, payment);
            return new CompleteCheckoutResponse(
                    "FAILED",
                    payment.id(),
                    payment.failureCode(),
                    payment.failureMessage(),
                    true,
                    null);
        } catch (RuntimeException ex) {
            releaseClaim(id, attemptId);
            throw ex;
        }
    }

    public CancelCheckoutResponse cancel(String id, String secret) {
        CheckoutSessionItem item = authenticator.authenticate(id, secret);
        if (CheckoutSessionStatus.CANCELLED.name().equals(item.getStatus()))
            return new CancelCheckoutResponse(item.getCancelUrl());
        long now = now();
        try {
            dynamo.updateItem(
                    UpdateItemRequest.builder()
                            .tableName(sessions.tableName())
                            .key(key(id))
                            .conditionExpression(
                                    "(#s=:created OR #s=:progress) AND expires_at>:now AND "
                                            + "attribute_not_exists(active_completion_attempt_id)")
                            .updateExpression("SET #s=:cancelled, updated_at=:now")
                            .expressionAttributeNames(Map.of("#s", "status"))
                            .expressionAttributeValues(
                                    Map.of(
                                            ":created",
                                            s("CREATED"),
                                            ":progress",
                                            s("IN_PROGRESS"),
                                            ":cancelled",
                                            s("CANCELLED"),
                                            ":now",
                                            n(now)))
                            .build());
        } catch (ConditionalCheckFailedException race) {
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

    private void finishCaptured(String id, String attemptId, String tokenHash, String paymentId) {
        long now = now();
        updateTerminal(
                id,
                attemptId,
                "SET #s=:completed, payment_id=:payment, completed_at=:now, updated_at=:now, "
                        + "last_completion_token_hash=:tokenHash, last_completion_status=:completed "
                        + "REMOVE active_completion_attempt_id, active_completion_token_hash, completion_lease_until",
                Map.of(
                        ":completed",
                        s("COMPLETED"),
                        ":payment",
                        s(paymentId),
                        ":now",
                        n(now),
                        ":tokenHash",
                        s(tokenHash)));
    }

    private void finishFailed(
            String id, String attemptId, String tokenHash, PaymentClient.PaymentResult p) {
        long now = now();
        Map<String, AttributeValue> values = new java.util.HashMap<>();
        values.put(":progress", s("IN_PROGRESS"));
        values.put(":payment", s(p.id()));
        values.put(":now", n(now));
        values.put(":tokenHash", s(tokenHash));
        values.put(":failed", s("FAILED"));
        values.put(":retry", AttributeValue.fromBool(true));
        values.put(":code", s(p.failureCode() == null ? "payment_failed" : p.failureCode()));
        values.put(
                ":message", s(p.failureMessage() == null ? "Payment failed." : p.failureMessage()));
        updateTerminal(
                id,
                attemptId,
                "SET #s=:progress, updated_at=:now, last_completion_token_hash=:tokenHash, "
                        + "last_completion_payment_id=:payment, last_completion_status=:failed, last_failure_code=:code, "
                        + "last_failure_message=:message, last_retry_allowed=:retry "
                        + "REMOVE active_completion_attempt_id, active_completion_token_hash, completion_lease_until",
                values);
    }

    private void updateTerminal(
            String id, String attemptId, String expression, Map<String, AttributeValue> values) {
        Map<String, AttributeValue> all = new java.util.HashMap<>(values);
        all.put(":attempt", s(attemptId));
        dynamo.updateItem(
                UpdateItemRequest.builder()
                        .tableName(sessions.tableName())
                        .key(key(id))
                        .conditionExpression("active_completion_attempt_id=:attempt")
                        .updateExpression(expression)
                        .expressionAttributeNames(Map.of("#s", "status"))
                        .expressionAttributeValues(all)
                        .build());
    }

    private void releaseClaim(String id, String attemptId) {
        try {
            dynamo.updateItem(
                    UpdateItemRequest.builder()
                            .tableName(sessions.tableName())
                            .key(key(id))
                            .conditionExpression("active_completion_attempt_id=:attempt")
                            .updateExpression(
                                    "REMOVE active_completion_attempt_id, active_completion_token_hash, completion_lease_until")
                            .expressionAttributeValues(Map.of(":attempt", s(attemptId)))
                            .build());
        } catch (ConditionalCheckFailedException ignored) {
        }
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
