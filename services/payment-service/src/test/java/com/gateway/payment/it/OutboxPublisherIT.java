package com.gateway.payment.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.payment.PaymentServiceApplication;
import com.gateway.payment.domain.OutboxRecord;
import com.gateway.payment.persistence.OutboxRepository;
import com.gateway.shared.testing.AbstractPostgresLocalStackIT;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Integration test for the outbox publisher (task 4.3).
 *
 * <p>Proves an outbox row written directly to Postgres is picked up by the {@code @Scheduled}
 * poller, published to the real SNS topic (LocalStack), delivered to a subscribed SQS queue, and
 * marked {@code published_at} on success.
 */
@TestInstance(Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = PaymentServiceApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT)
class OutboxPublisherIT extends AbstractPostgresLocalStackIT {

    private static final String QUEUE_NAME = "outbox-publisher-it-queue";
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

    private static String queueUrl;

    @DynamicPropertySource
    static void provisionSnsAndQueue(DynamicPropertyRegistry registry) {
        try (SnsClient snsClient = snsClient();
                SqsClient sqsClient = sqsClient()) {
            String topicArn = snsClient.createTopic(r -> r.name("payment-events")).topicArn();

            queueUrl = sqsClient.createQueue(r -> r.queueName(QUEUE_NAME)).queueUrl();
            String queueArn =
                    sqsClient
                            .getQueueAttributes(
                                    r ->
                                            r.queueUrl(queueUrl)
                                                    .attributeNames(QueueAttributeName.QUEUE_ARN))
                            .attributes()
                            .get(QueueAttributeName.QUEUE_ARN);

            snsClient.subscribe(r -> r.topicArn(topicArn).protocol("sqs").endpoint(queueArn));

            registry.add("gateway.payment.outbox.topic-arn", () -> topicArn);
        }
        registry.add("shared.security.merchant-service.base-url", () -> "http://localhost:19999");
        registry.add("gateway.aws.region", () -> "eu-north-1");
        registry.add("gateway.aws.sns.endpoint", AbstractPostgresLocalStackIT::snsEndpoint);
        registry.add("gateway.aws.credentials.access-key", () -> "test");
        registry.add("gateway.aws.credentials.secret-key", () -> "test");
    }

    private static SnsClient snsClient() {
        return SnsClient.builder()
                .region(Region.of("eu-north-1"))
                .endpointOverride(URI.create(AbstractPostgresLocalStackIT.snsEndpoint()))
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create("test", "test")))
                .build();
    }

    private static SqsClient sqsClient() {
        return SqsClient.builder()
                .region(Region.of("eu-north-1"))
                .endpointOverride(URI.create(AbstractPostgresLocalStackIT.sqsEndpoint()))
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create("test", "test")))
                .build();
    }

    @Autowired private OutboxRepository outboxRepository;

    @Test
    void publishesOutboxRowAndMarksPublished() throws InterruptedException {
        OutboxRecord row =
                outboxRepository.save(
                        new OutboxRecord(
                                "pay_outbox_it_01",
                                "payment.captured",
                                Map.of(
                                        "id",
                                        "evt_outbox_it_01",
                                        "type",
                                        "payment.captured",
                                        "created",
                                        1748160000,
                                        "data",
                                        Map.of("object", Map.of("id", "pay_outbox_it_01")))));

        boolean delivered = pollUntil(this::messageArrivedInQueue);
        assertThat(delivered).as("event delivered to SQS via the SNS subscription").isTrue();

        boolean published = pollUntil(() -> isMarkedPublished(row.getId()));
        assertThat(published).as("outbox row marked published_at").isTrue();
    }

    private boolean messageArrivedInQueue() {
        try (SqsClient sqsClient = sqsClient()) {
            List<Message> messages =
                    sqsClient
                            .receiveMessage(
                                    r ->
                                            r.queueUrl(queueUrl)
                                                    .maxNumberOfMessages(10)
                                                    .waitTimeSeconds(1))
                            .messages();
            return messages.stream().anyMatch(m -> m.body().contains("evt_outbox_it_01"));
        }
    }

    private boolean isMarkedPublished(Long id) {
        Optional<OutboxRecord> refetched = outboxRepository.findById(id);
        return refetched.isPresent() && refetched.get().getPublishedAt() != null;
    }

    private boolean pollUntil(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(POLL_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        return condition.getAsBoolean();
    }
}
