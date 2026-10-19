package com.gateway.checkout.event;

import static com.gateway.checkout.persistence.CheckoutTransitions.n;
import static com.gateway.checkout.persistence.CheckoutTransitions.s;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.sns.SnsClient;

@Component
@ConditionalOnProperty(name = "gateway.checkout.workers.enabled", matchIfMissing = true)
public class CheckoutOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(CheckoutOutboxPublisher.class);
    private final DynamoDbClient dynamo;
    private final SnsClient sns;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final String topic;
    private final int batchSize;
    private final long retention;
    private final AtomicLong lag = new AtomicLong();

    public CheckoutOutboxPublisher(
            DynamoDbClient dynamo,
            SnsClient sns,
            Clock clock,
            MeterRegistry metrics,
            @Value("${gateway.checkout.outbox.topic-arn}") String topic,
            @Value("${gateway.checkout.outbox.batch-size:50}") int batchSize,
            @Value("${gateway.checkout.outbox.retention-seconds:2592000}") long retention) {
        this.dynamo = dynamo;
        this.sns = sns;
        this.clock = clock;
        this.metrics = metrics;
        this.topic = topic;
        this.batchSize = batchSize;
        this.retention = retention;
        if (topic.isBlank() || batchSize < 1 || retention < 1)
            throw new IllegalArgumentException("Invalid checkout outbox configuration");
        metrics.gauge("outbox.lag.seconds", lag);
    }

    @Scheduled(fixedDelayString = "${gateway.checkout.outbox.poll-ms:1000}")
    public void publishBatch() {
        long now = now();
        var request =
                QueryRequest.builder()
                        .tableName("checkout_outbox")
                        .indexName("pending-index")
                        .keyConditionExpression("pending=:pending AND available_at<=:now")
                        .expressionAttributeValues(Map.of(":pending", s("PENDING"), ":now", n(now)))
                        .limit(batchSize)
                        .build();
        for (var candidate : dynamo.query(request).items()) publish(candidate.get("event_id").s());
        var oldest =
                dynamo.query(
                                r ->
                                        r.tableName("checkout_outbox")
                                                .indexName("pending-age-index")
                                                .keyConditionExpression("pending=:pending")
                                                .expressionAttributeValues(
                                                        Map.of(":pending", s("PENDING")))
                                                .limit(1))
                        .items();
        lag.set(
                oldest.isEmpty()
                        ? 0
                        : Math.max(
                                0,
                                now() - Long.parseLong(oldest.getFirst().get("created_at").n())));
    }

    private void publish(String id) {
        String owner = UUID.randomUUID().toString();
        var key = Map.of("event_id", s(id));
        Map<String, AttributeValue> item;
        try {
            item =
                    dynamo.updateItem(
                                    r ->
                                            r.tableName("checkout_outbox")
                                                    .key(key)
                                                    .conditionExpression(
                                                            "pending=:pending AND available_at<=:now AND attribute_not_exists(published_at)")
                                                    .updateExpression(
                                                            "SET lease_owner=:owner, available_at=:until ADD attempts :one")
                                                    .expressionAttributeValues(
                                                            Map.of(
                                                                    ":pending",
                                                                    s("PENDING"),
                                                                    ":now",
                                                                    n(now()),
                                                                    ":owner",
                                                                    s(owner),
                                                                    ":until",
                                                                    n(now() + 30),
                                                                    ":one",
                                                                    n(1)))
                                                    .returnValues(ReturnValue.ALL_NEW))
                            .attributes();
        } catch (ConditionalCheckFailedException lost) {
            return;
        }
        try {
            sns.publish(r -> r.topicArn(topic).message(item.get("payload").s()));
            long now = now();
            dynamo.updateItem(
                    r ->
                            r.tableName("checkout_outbox")
                                    .key(key)
                                    .conditionExpression(
                                            "lease_owner=:owner AND attribute_not_exists(published_at)")
                                    .updateExpression(
                                            "SET published_at=:now, delete_at=:delete REMOVE pending, available_at, lease_owner")
                                    .expressionAttributeValues(
                                            Map.of(
                                                    ":owner",
                                                    s(owner),
                                                    ":now",
                                                    n(now),
                                                    ":delete",
                                                    n(now + retention))));
            metrics.counter("outbox.publish.count").increment();
        } catch (RuntimeException failure) {
            metrics.counter("outbox.publish.errors").increment();
            log.warn("checkout.outbox_publish_failed event_id={}", id);
            long attempts = Long.parseLong(item.get("attempts").n());
            long delay = Math.min(300, 1L << Math.min(8, attempts));
            try {
                dynamo.updateItem(
                        r ->
                                r.tableName("checkout_outbox")
                                        .key(key)
                                        .conditionExpression(
                                                "lease_owner=:owner AND attribute_not_exists(published_at)")
                                        .updateExpression(
                                                "SET available_at=:next REMOVE lease_owner")
                                        .expressionAttributeValues(
                                                Map.of(
                                                        ":owner",
                                                        s(owner),
                                                        ":next",
                                                        n(now() + delay))));
            } catch (RuntimeException ignored) {
                // If the acknowledgement was ambiguous, or storage is down, the lease recovers it.
            }
        }
    }

    private long now() {
        return clock.instant().getEpochSecond();
    }
}
