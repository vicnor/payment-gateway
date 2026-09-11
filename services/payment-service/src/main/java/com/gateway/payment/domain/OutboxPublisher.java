package com.gateway.payment.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.payment.config.OutboxProperties;
import com.gateway.payment.persistence.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

/**
 * Polls the {@code outbox} table and publishes unpublished rows to SNS.
 *
 * <p>Runs every second, claiming up to {@code gateway.payment.outbox.publisher.batch-size} rows
 * with {@code SELECT ... FOR UPDATE SKIP LOCKED} so multiple instances of this service can run the
 * poller concurrently without double-claiming a row.
 *
 * <p>The claim, every SNS publish call, and the {@code published_at} updates for the whole batch
 * happen in a single transaction. If any publish call fails, the entire batch rolls back and is
 * retried on the next tick — including rows already published to SNS earlier in the same batch.
 * This is safe because each event's id is fixed when the outbox row is written ({@link
 * PaymentWriter}), and webhook-service dedupes deliveries by event id, so a re-publish of an
 * already-delivered event is a harmless retry, not a duplicate side effect.
 */
@Component
@ConditionalOnProperty(
        prefix = "gateway.payment.outbox.publisher",
        name = "enabled",
        matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outboxRepository;
    private final SnsClient snsClient;
    private final ObjectMapper objectMapper;
    private final OutboxProperties properties;
    private final Counter publishedCounter;
    private final Counter errorCounter;
    private final AtomicLong lagSeconds = new AtomicLong(0);

    public OutboxPublisher(
            OutboxRepository outboxRepository,
            SnsClient snsClient,
            ObjectMapper objectMapper,
            OutboxProperties properties,
            MeterRegistry meterRegistry) {
        this.outboxRepository = outboxRepository;
        this.snsClient = snsClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.publishedCounter = meterRegistry.counter("outbox.publish.count");
        this.errorCounter = meterRegistry.counter("outbox.publish.errors");
        meterRegistry.gauge("outbox.lag.seconds", lagSeconds);
    }

    @Scheduled(fixedRate = 1000)
    @Transactional
    public void publishBatch() {
        List<OutboxRecord> claimed =
                outboxRepository.claimUnpublished(properties.publisher().batchSize());

        if (!claimed.isEmpty()) {
            try {
                Instant now = Instant.now();
                for (OutboxRecord record : claimed) {
                    publish(record);
                    record.markPublished(now);
                }
                publishedCounter.increment(claimed.size());
            } catch (RuntimeException e) {
                errorCounter.increment();
                log.error(
                        "outbox batch publish failed, rolling back batch of {} rows",
                        claimed.size(),
                        e);
                throw e;
            }
        }

        updateLag();
    }

    private void publish(OutboxRecord record) {
        String body;
        try {
            body = objectMapper.writeValueAsString(record.getPayload());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "failed to serialize outbox payload id=" + record.getId(), e);
        }

        snsClient.publish(
                PublishRequest.builder().topicArn(properties.topicArn()).message(body).build());
    }

    private void updateLag() {
        Instant oldest = outboxRepository.oldestUnpublishedCreatedAt().orElse(null);
        long lag = oldest == null ? 0 : Duration.between(oldest, Instant.now()).toSeconds();
        lagSeconds.set(Math.max(lag, 0));
    }
}
