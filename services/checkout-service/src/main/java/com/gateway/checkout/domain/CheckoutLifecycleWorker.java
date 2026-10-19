package com.gateway.checkout.domain;

import static com.gateway.checkout.persistence.CheckoutTransitions.*;

import com.gateway.checkout.client.PaymentClient;
import com.gateway.checkout.persistence.CheckoutTransitions;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

@Component
@ConditionalOnProperty(name = "gateway.checkout.workers.enabled", matchIfMissing = true)
public class CheckoutLifecycleWorker {
    private static final Logger log = LoggerFactory.getLogger(CheckoutLifecycleWorker.class);
    private final DynamoDbClient dynamo;
    private final CheckoutTransitions transitions;
    private final PaymentClient payments;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final long overdueSeconds;
    private final AtomicLong overdue = new AtomicLong();
    private final AtomicLong oldest = new AtomicLong();

    public CheckoutLifecycleWorker(
            DynamoDbClient dynamo,
            CheckoutTransitions transitions,
            PaymentClient payments,
            Clock clock,
            MeterRegistry metrics,
            @Value("${gateway.checkout.recovery.overdue-seconds:300}") long overdueSeconds) {
        this.dynamo = dynamo;
        this.transitions = transitions;
        this.payments = payments;
        this.clock = clock;
        this.metrics = metrics;
        this.overdueSeconds = overdueSeconds;
        metrics.gauge("checkout.payment_resolution.overdue", overdue);
        metrics.gauge("checkout.payment_resolution.oldest.seconds", oldest);
    }

    @Scheduled(fixedDelayString = "${gateway.checkout.expiration.poll-ms:30000}")
    public void expireSessions() {
        for (String status : java.util.List.of("CREATED", "IN_PROGRESS")) {
            var request =
                    QueryRequest.builder()
                            .tableName("checkout_sessions")
                            .indexName("status-expiry-index")
                            .keyConditionExpression("#s=:status AND expires_at<=:now")
                            .expressionAttributeNames(Map.of("#s", "status"))
                            .expressionAttributeValues(
                                    Map.of(":status", s(status), ":now", n(now())))
                            .limit(50)
                            .build();
            for (var page : dynamo.queryPaginator(request))
                for (var candidate : page.items()) {
                    try {
                        transitions.expire(transitions.get(candidate.get("session_id").s()));
                    } catch (RuntimeException failure) {
                        metrics.counter("checkout.expiration.errors").increment();
                    }
                }
        }
    }

    @Scheduled(fixedDelayString = "${gateway.checkout.recovery.poll-ms:10000}")
    public void recoverPayments() {
        long count = 0, age = 0;
        var query =
                QueryRequest.builder()
                        .tableName("checkout_sessions")
                        .indexName("recovery-index")
                        .keyConditionExpression("recovery_bucket=:bucket")
                        .expressionAttributeValues(Map.of(":bucket", s("ACTIVE")))
                        .limit(50)
                        .build();
        for (var page : dynamo.queryPaginator(query))
            for (var candidate : page.items()) {
                String id = candidate.get("session_id").s();
                var item =
                        dynamo.getItem(
                                        r ->
                                                r.tableName("checkout_sessions")
                                                        .key(key(id))
                                                        .consistentRead(true))
                                .item();
                if (!item.containsKey("active_completion_attempt_id")) continue;
                long elapsed =
                        Math.max(0, now() - Long.parseLong(item.get("completion_started_at").n()));
                age = Math.max(age, elapsed);
                if (elapsed >= overdueSeconds) {
                    count++;
                    logOverdue(id, item, elapsed);
                }
                try {
                    recover(id, item);
                } catch (RuntimeException failure) {
                    metrics.counter("checkout.payment_resolution.errors").increment();
                }
            }
        overdue.set(count);
        oldest.set(age);
    }

    private void recover(String id, Map<String, AttributeValue> item) {
        String attempt = item.get("active_completion_attempt_id").s();
        long failures =
                item.containsKey("recovery_attempts")
                        ? Long.parseLong(item.get("recovery_attempts").n())
                        : 0;
        long delay = Math.min(300, 10L << Math.min(5, failures));
        try {
            dynamo.updateItem(
                    r ->
                            r.tableName("checkout_sessions")
                                    .key(key(id))
                                    .conditionExpression(
                                            "active_completion_attempt_id=:attempt AND recovery_next_at<=:now")
                                    .updateExpression(
                                            "SET recovery_next_at=:next ADD recovery_attempts :one")
                                    .expressionAttributeValues(
                                            Map.of(
                                                    ":attempt",
                                                    s(attempt),
                                                    ":now",
                                                    n(now()),
                                                    ":next",
                                                    n(now() + Math.max(60, delay)),
                                                    ":one",
                                                    n(1))));
        } catch (ConditionalCheckFailedException lost) {
            return;
        }
        var result = payments.find(attempt, id, item.get("merchant_id").s());
        if (result.isEmpty()) return;
        var session =
                com.gateway.checkout.persistence.CheckoutSessionItem.TABLE_SCHEMA.mapToItem(item);
        if (transitions.resolve(session, result.get())) {
            metrics.counter("checkout.payment_resolution.recovered").increment();
            log.info(
                    "checkout.payment_resolution_recovered session_id={} completion_attempt_id={}",
                    id,
                    attempt);
            transitions.expire(transitions.get(id));
        }
    }

    private void logOverdue(String id, Map<String, AttributeValue> item, long elapsed) {
        if (item.containsKey("overdue_logged")) return;
        String attempt = item.get("active_completion_attempt_id").s();
        try {
            dynamo.updateItem(
                    r ->
                            r.tableName("checkout_sessions")
                                    .key(key(id))
                                    .conditionExpression(
                                            "active_completion_attempt_id=:attempt AND attribute_not_exists(overdue_logged)")
                                    .updateExpression("SET overdue_logged=:yes")
                                    .expressionAttributeValues(
                                            Map.of(
                                                    ":attempt",
                                                    s(attempt),
                                                    ":yes",
                                                    AttributeValue.fromBool(true))));
            log.error(
                    "checkout.payment_resolution_overdue session_id={} completion_attempt_id={} elapsed_seconds={}",
                    id,
                    attempt,
                    elapsed);
        } catch (ConditionalCheckFailedException ignored) {
        }
    }

    private long now() {
        return clock.instant().getEpochSecond();
    }
}
