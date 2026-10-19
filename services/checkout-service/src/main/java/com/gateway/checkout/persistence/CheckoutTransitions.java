package com.gateway.checkout.persistence;

import com.gateway.checkout.client.PaymentClient;
import com.gateway.checkout.config.CheckoutProperties;
import com.gateway.checkout.event.CheckoutEventFactory;
import com.gateway.shared.events.EventType;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/** All terminal transitions share conditional writes and atomically append their event. */
@Component
public class CheckoutTransitions {
    private final DynamoDbClient dynamo;
    private final DynamoDbTable<CheckoutSessionItem> sessions;
    private final CheckoutEventFactory events;
    private final Clock clock;
    private final CheckoutProperties properties;
    private static final String OPEN = "(#s=:created OR #s=:progress)";
    private static final String CLEAR =
            "active_completion_attempt_id, active_completion_token_hash, completion_lease_until, recovery_bucket, completion_started_at, recovery_next_at, recovery_attempts, overdue_logged";

    public CheckoutTransitions(
            DynamoDbClient dynamo,
            @Qualifier("checkoutSessionsTable") DynamoDbTable<CheckoutSessionItem> sessions,
            CheckoutEventFactory events,
            Clock clock,
            CheckoutProperties properties) {
        this.dynamo = dynamo;
        this.sessions = sessions;
        this.events = events;
        this.clock = clock;
        this.properties = properties;
    }

    public CheckoutSessionItem get(String id) {
        return sessions.getItem(r -> r.key(k -> k.partitionValue(id)).consistentRead(true));
    }

    public boolean cancel(CheckoutSessionItem item) {
        return terminate(item, false);
    }

    public CheckoutSessionItem expire(CheckoutSessionItem item) {
        if (item != null
                && item.getExpiresAt() <= now()
                && ("CREATED".equals(item.getStatus()) || "IN_PROGRESS".equals(item.getStatus()))) {
            terminate(item, true);
            return get(item.getSessionId());
        }
        return item;
    }

    private boolean terminate(CheckoutSessionItem item, boolean expired) {
        long now = now();
        String status = expired ? "EXPIRED" : "CANCELLED";
        var values = base(now);
        values.put(":terminal", s(status));
        return write(
                item,
                OPEN
                        + " AND attribute_not_exists(active_completion_attempt_id) AND expires_at"
                        + (expired ? "<=:now" : ">:now"),
                "SET #s=:terminal, updated_at=:now",
                values,
                events.create(
                        item,
                        expired ? EventType.CHECKOUT_EXPIRED : EventType.CHECKOUT_CANCELLED,
                        status,
                        null,
                        now));
    }

    /** A lease only schedules recovery; it never authorizes another charge. */
    public void claim(String id, String attempt, String tokenHash) {
        long now = now();
        var values = base(now);
        values.put(":attempt", s(attempt));
        values.put(":hash", s(tokenHash));
        values.put(":lease", n(now + 60));
        values.put(":bucket", s("ACTIVE"));
        dynamo.updateItem(
                r ->
                        r.tableName("checkout_sessions")
                                .key(key(id))
                                .conditionExpression(
                                        OPEN
                                                + " AND expires_at>:now AND attribute_not_exists(active_completion_attempt_id)")
                                .updateExpression(
                                        "SET #s=:progress, updated_at=:now, active_completion_attempt_id=:attempt, "
                                                + "active_completion_token_hash=:hash, completion_lease_until=:lease, "
                                                + "completion_started_at=:now, recovery_next_at=:lease, recovery_bucket=:bucket REMOVE delete_at")
                                .expressionAttributeNames(Map.of("#s", "status"))
                                .expressionAttributeValues(values));
    }

    public boolean resolve(CheckoutSessionItem item, PaymentClient.PaymentResult result) {
        if (item == null || item.getActiveCompletionAttemptId() == null) return false;
        boolean captured = "CAPTURED".equals(result.status());
        if (!captured && !"FAILED".equals(result.status()))
            throw new IllegalArgumentException("Payment outcome is not terminal");
        long now = now();
        var values = base(now);
        values.put(":attempt", s(item.getActiveCompletionAttemptId()));
        values.put(":hash", s(item.getActiveCompletionTokenHash()));
        values.put(":payment", s(result.id()));
        values.put(":retention", n(now + properties.retention().toSeconds()));
        values.put(":result", s(captured ? "COMPLETED" : "FAILED"));
        String expression =
                "SET updated_at=:now, delete_at=:retention, last_completion_token_hash=:hash, "
                        + "last_completion_status=:result, last_completion_payment_id=:payment, ";
        CheckoutOutboxItem event = null;
        if (captured) {
            expression += "#s=:result, payment_id=:payment, completed_at=:now ";
            event =
                    events.create(
                            item, EventType.CHECKOUT_COMPLETED, "COMPLETED", result.id(), now);
        } else {
            expression +=
                    "#s=:progress, last_failure_code=:code, last_failure_message=:message, last_retry_allowed=:retry ";
            values.put(
                    ":code",
                    s(result.failureCode() == null ? "payment_failed" : result.failureCode()));
            values.put(
                    ":message",
                    s(
                            result.failureMessage() == null
                                    ? "Payment failed."
                                    : result.failureMessage()));
            values.put(":retry", AttributeValue.fromBool(true));
        }
        return write(
                item,
                OPEN + " AND active_completion_attempt_id=:attempt",
                expression + "REMOVE " + CLEAR,
                values,
                event);
    }

    /** Only a definitive pre-authorization rejection may release a claim without a result. */
    public void release(String id, String attempt) {
        try {
            dynamo.updateItem(
                    r ->
                            r.tableName("checkout_sessions")
                                    .key(key(id))
                                    .conditionExpression("active_completion_attempt_id=:attempt")
                                    .updateExpression("SET delete_at=:retention REMOVE " + CLEAR)
                                    .expressionAttributeValues(
                                            Map.of(
                                                    ":attempt",
                                                    s(attempt),
                                                    ":retention",
                                                    n(
                                                            now()
                                                                    + properties
                                                                            .retention()
                                                                            .toSeconds()))));
        } catch (ConditionalCheckFailedException ignored) {
        }
    }

    private boolean write(
            CheckoutSessionItem item,
            String condition,
            String expression,
            Map<String, AttributeValue> values,
            CheckoutOutboxItem event) {
        var update =
                Update.builder()
                        .tableName("checkout_sessions")
                        .key(key(item.getSessionId()))
                        .conditionExpression(condition)
                        .updateExpression(expression)
                        .expressionAttributeNames(Map.of("#s", "status"))
                        .expressionAttributeValues(values)
                        .build();
        var changes = new java.util.ArrayList<TransactWriteItem>();
        changes.add(TransactWriteItem.builder().update(update).build());
        if (event != null)
            changes.add(
                    TransactWriteItem.builder()
                            .put(
                                    Put.builder()
                                            .tableName("checkout_outbox")
                                            .item(
                                                    CheckoutOutboxItem.TABLE_SCHEMA.itemToMap(
                                                            event, true))
                                            .conditionExpression("attribute_not_exists(event_id)")
                                            .build())
                            .build());
        try {
            dynamo.transactWriteItems(r -> r.transactItems(changes));
            return true;
        } catch (TransactionCanceledException e) {
            if (!e.cancellationReasons().isEmpty()
                    && "ConditionalCheckFailed".equals(e.cancellationReasons().getFirst().code()))
                return false;
            throw e;
        }
    }

    private long now() {
        return clock.instant().getEpochSecond();
    }

    private static Map<String, AttributeValue> base(long now) {
        return new HashMap<>(
                Map.of(":created", s("CREATED"), ":progress", s("IN_PROGRESS"), ":now", n(now)));
    }

    public static Map<String, AttributeValue> key(String id) {
        return Map.of("session_id", s(id));
    }

    public static AttributeValue s(String v) {
        return AttributeValue.fromS(v);
    }

    public static AttributeValue n(long v) {
        return AttributeValue.fromN(Long.toString(v));
    }
}
