package com.gateway.checkout.it;

import static com.gateway.checkout.persistence.CheckoutTransitions.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.checkout.CheckoutServiceApplication;
import com.gateway.checkout.client.PaymentClient;
import com.gateway.checkout.domain.CheckoutLifecycleWorker;
import com.gateway.checkout.event.CheckoutEventFactory;
import com.gateway.checkout.event.CheckoutOutboxPublisher;
import com.gateway.checkout.persistence.*;
import com.gateway.shared.testing.AbstractDynamoIT;
import com.github.f4b6a3.ulid.UlidCreator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

@SpringBootTest(
        classes = CheckoutServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "gateway.checkout.workers.enabled=false")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CheckoutOutboxIT extends AbstractDynamoIT {
    @Autowired DynamoDbClient dynamo;
    @Autowired CheckoutTransitions transitions;
    @Autowired CheckoutEventFactory events;
    @Autowired ObjectMapper mapper;

    @Autowired
    @Qualifier("checkoutSessionsTable")
    DynamoDbTable<CheckoutSessionItem> sessions;

    @Autowired
    @Qualifier("checkoutOutboxTable")
    DynamoDbTable<CheckoutOutboxItem> outbox;

    @BeforeAll
    void tables() {
        for (var table : List.of(sessions, outbox)) {
            try {
                table.createTable();
            } catch (ResourceInUseException ignored) {
            }
        }
    }

    @BeforeEach
    void clear() {
        sessions.scan().items().forEach(sessions::deleteItem);
        outbox.scan().items().forEach(outbox::deleteItem);
    }

    @Test
    void cancellationAndExpiryAreAtomicAndIdempotent() throws Exception {
        var item = session();
        assertThat(transitions.cancel(item)).isTrue();
        assertThat(transitions.cancel(item)).isFalse();
        item.setExpiresAt(now() - 1);
        transitions.expire(item);
        assertThat(transitions.get(item.getSessionId()).getStatus()).isEqualTo("CANCELLED");
        var records = outbox.scan().items().stream().toList();
        assertThat(records).hasSize(1);
        var payload = mapper.readTree(records.getFirst().getPayload());
        assertThat(payload.path("type").asText()).isEqualTo("checkout.cancelled");
        assertThat(payload.path("data").path("object").path("merchant_id").asText())
                .isEqualTo("mer_test");
        assertThat(records.getFirst().getPayload())
                .doesNotContain("secret", "private", "customer", "metadata", "url", "token");
        assertThat(records.getFirst().getDeleteAt()).isNull();
    }

    @Test
    void invalidOutboxWriteRollsBackTransition() {
        var item = session();
        // Deterministic validation failure in the outbox item, after assembling the transaction.
        var badFactory = mock(CheckoutEventFactory.class);
        var badEvent = new CheckoutOutboxItem();
        badEvent.setPayload("missing primary key");
        when(badFactory.create(any(), any(), any(), any(), anyLong())).thenReturn(badEvent);
        var broken =
                new CheckoutTransitions(
                        dynamo,
                        sessions,
                        badFactory,
                        Clock.systemUTC(),
                        new com.gateway.checkout.config.CheckoutProperties(
                                "https://checkout.test",
                                java.time.Duration.ofMinutes(30),
                                java.time.Duration.ofDays(30),
                                99999999,
                                null,
                                null,
                                null));
        assertThatThrownBy(() -> broken.cancel(item)).isInstanceOf(DynamoDbException.class);
        assertThat(transitions.get(item.getSessionId()).getStatus()).isEqualTo("CREATED");
        assertThat(outbox.scan().items().stream().count()).isZero();
    }

    @Test
    void unresolvedAttemptCannotExpireCancelOrBeReplacedAndCanCompleteAfterDeadline() {
        var item = session();
        transitions.claim(item.getSessionId(), "ca_test", "digest");
        dynamo.updateItem(
                r ->
                        r.tableName("checkout_sessions")
                                .key(key(item.getSessionId()))
                                .updateExpression(
                                        "SET expires_at=:past, completion_lease_until=:past")
                                .expressionAttributeValues(Map.of(":past", n(now() - 1))));
        var active = transitions.get(item.getSessionId());
        assertThat(active.getDeleteAt()).isNull();
        assertThat(transitions.cancel(active)).isFalse();
        assertThat(transitions.expire(active).getStatus()).isEqualTo("IN_PROGRESS");
        assertThatThrownBy(() -> transitions.claim(item.getSessionId(), "ca_other", "digest2"))
                .isInstanceOf(ConditionalCheckFailedException.class);
        var result = new PaymentClient.PaymentResult("pay_recovered", "CAPTURED", null, null);
        assertThat(transitions.resolve(active, result)).isTrue();
        assertThat(transitions.resolve(active, result)).isFalse();
        assertThat(transitions.get(item.getSessionId()).getStatus()).isEqualTo("COMPLETED");
        assertThat(transitions.get(item.getSessionId()).getDeleteAt()).isGreaterThan(now());
        assertThat(outbox.scan().items().stream().toList()).hasSize(1);
    }

    @Test
    void sweeperExpiresAbandonedSessionsAndRecoversKnownResultsOnly() {
        var abandoned = session();
        abandoned.setExpiresAt(now() - 1);
        sessions.putItem(abandoned);
        var pending = session();
        transitions.claim(pending.getSessionId(), "ca_pending", "digest");
        dynamo.updateItem(
                r ->
                        r.tableName("checkout_sessions")
                                .key(key(pending.getSessionId()))
                                .updateExpression(
                                        "SET completion_started_at=:past, recovery_next_at=:past")
                                .expressionAttributeValues(Map.of(":past", n(now() - 600))));
        var client = mock(PaymentClient.class);
        when(client.find("ca_pending", pending.getSessionId(), "mer_test"))
                .thenReturn(Optional.empty());
        var metrics = new SimpleMeterRegistry();
        var worker =
                new CheckoutLifecycleWorker(
                        dynamo, transitions, client, Clock.systemUTC(), metrics, 300);
        worker.expireSessions();
        worker.recoverPayments();
        assertThat(transitions.get(abandoned.getSessionId()).getStatus()).isEqualTo("EXPIRED");
        assertThat(transitions.get(pending.getSessionId()).getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(metrics.get("checkout.payment_resolution.overdue").gauge().value()).isEqualTo(1);
        worker.recoverPayments();
        verify(client, times(1)).find(anyString(), anyString(), anyString());
        when(client.find("ca_pending", pending.getSessionId(), "mer_test"))
                .thenReturn(
                        Optional.of(
                                new PaymentClient.PaymentResult(
                                        "pay_known", "CAPTURED", null, null)));
        var later =
                new CheckoutLifecycleWorker(
                        dynamo,
                        transitions,
                        client,
                        Clock.offset(Clock.systemUTC(), java.time.Duration.ofMinutes(10)),
                        new SimpleMeterRegistry(),
                        300);
        later.recoverPayments();
        assertThat(transitions.get(pending.getSessionId()).getStatus()).isEqualTo("COMPLETED");
        verify(client, never()).create(any());
    }

    @Test
    void declinedRecoveryExpiresAfterDeadlineWithoutCompletedEvent() {
        var item = session();
        transitions.claim(item.getSessionId(), "ca_declined", "digest");
        dynamo.updateItem(
                r ->
                        r.tableName("checkout_sessions")
                                .key(key(item.getSessionId()))
                                .updateExpression("SET expires_at=:past")
                                .expressionAttributeValues(Map.of(":past", n(now() - 1))));
        transitions.resolve(
                transitions.get(item.getSessionId()),
                new PaymentClient.PaymentResult(
                        "pay_declined", "FAILED", "card_declined", "Declined"));
        transitions.expire(transitions.get(item.getSessionId()));
        assertThat(outbox.scan().items().stream().toList())
                .singleElement()
                .satisfies(
                        row ->
                                assertThat(row.getPayload())
                                        .contains("checkout.expired")
                                        .doesNotContain("checkout.completed"));
    }

    @Test
    void publisherRetriesSamePayloadAndRecoversAbandonedLease() {
        var item = session();
        transitions.cancel(item);
        var row = outbox.scan().items().stream().findFirst().orElseThrow();
        var sns = mock(SnsClient.class);
        doThrow(new IllegalStateException("unavailable"))
                .doAnswer(call -> null)
                .when(sns)
                .publish(any(java.util.function.Consumer.class));
        var metrics = new SimpleMeterRegistry();
        var publisher =
                new CheckoutOutboxPublisher(
                        dynamo, sns, Clock.systemUTC(), metrics, "topic", 50, 2592000);
        publisher.publishBatch();
        assertThat(
                        outbox.getItem(r -> r.key(k -> k.partitionValue(row.getEventId())))
                                .getPublishedAt())
                .isNull();
        // Simulate a process dying while holding a claim. A later instance reclaims it.
        dynamo.updateItem(
                r ->
                        r.tableName("checkout_outbox")
                                .key(Map.of("event_id", s(row.getEventId())))
                                .updateExpression("SET lease_owner=:owner, available_at=:past")
                                .expressionAttributeValues(
                                        Map.of(
                                                ":owner",
                                                s("dead-process"),
                                                ":past",
                                                n(now() - 1))));
        publisher.publishBatch();
        var published = outbox.getItem(r -> r.key(k -> k.partitionValue(row.getEventId())));
        assertThat(published.getPublishedAt()).isNotNull();
        assertThat(published.getPayload()).isEqualTo(row.getPayload());
        assertThat(published.getDeleteAt()).isEqualTo(published.getPublishedAt() + 2592000);
        assertThat(published.getPending()).isNull();
        publisher.publishBatch();
        verify(sns, times(2)).publish(any(java.util.function.Consumer.class));
    }

    @Test
    void publishesExactEventThroughSnsToSqs() throws Exception {
        try (var aws =
                new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.2"))
                        .withServices(
                                LocalStackContainer.Service.SNS, LocalStackContainer.Service.SQS)) {
            aws.start();
            var credentials =
                    StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
            try (var sns =
                            SnsClient.builder()
                                    .region(Region.EU_NORTH_1)
                                    .credentialsProvider(credentials)
                                    .endpointOverride(
                                            aws.getEndpointOverride(
                                                    LocalStackContainer.Service.SNS))
                                    .build();
                    var sqs =
                            SqsClient.builder()
                                    .region(Region.EU_NORTH_1)
                                    .credentialsProvider(credentials)
                                    .endpointOverride(
                                            aws.getEndpointOverride(
                                                    LocalStackContainer.Service.SQS))
                                    .build()) {
                String topic = sns.createTopic(r -> r.name("checkout-events")).topicArn();
                String queue = sqs.createQueue(r -> r.queueName("checkout-outbox-it")).queueUrl();
                String arn =
                        sqs.getQueueAttributes(
                                        r ->
                                                r.queueUrl(queue)
                                                        .attributeNames(
                                                                QueueAttributeName.QUEUE_ARN))
                                .attributes()
                                .get(QueueAttributeName.QUEUE_ARN);
                sns.subscribe(r -> r.topicArn(topic).protocol("sqs").endpoint(arn));
                var item = session();
                transitions.cancel(item);
                var row = outbox.scan().items().stream().findFirst().orElseThrow();
                new CheckoutOutboxPublisher(
                                dynamo,
                                sns,
                                Clock.systemUTC(),
                                new SimpleMeterRegistry(),
                                topic,
                                50,
                                2592000)
                        .publishBatch();
                var messages =
                        sqs.receiveMessage(r -> r.queueUrl(queue).waitTimeSeconds(10)).messages();
                assertThat(messages).hasSize(1);
                assertThat(mapper.readTree(messages.getFirst().body()).path("Message").asText())
                        .isEqualTo(row.getPayload());
            }
        }
    }

    @Test
    void concurrentPublishersOnlyPublishTheCurrentClaim() throws Exception {
        transitions.cancel(session());
        var sns = mock(SnsClient.class);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        doAnswer(
                        call -> {
                            entered.countDown();
                            if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                                throw new IllegalStateException("test timed out");
                            return null;
                        })
                .when(sns)
                .publish(any(java.util.function.Consumer.class));
        var publisher =
                new CheckoutOutboxPublisher(
                        dynamo,
                        sns,
                        Clock.systemUTC(),
                        new SimpleMeterRegistry(),
                        "topic",
                        50,
                        2592000);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(publisher::publishBatch);
            try {
                assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                publisher.publishBatch();
            } finally {
                release.countDown();
            }
            first.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        verify(sns, times(1)).publish(any(java.util.function.Consumer.class));
    }

    @Test
    void successfulPublishWithLostAcknowledgementReplaysIdenticalEventAfterLease() {
        transitions.cancel(session());
        var row = outbox.scan().items().stream().findFirst().orElseThrow();
        var unstable =
                mock(DynamoDbClient.class, org.mockito.AdditionalAnswers.delegatesTo(dynamo));
        doAnswer(
                        call -> {
                            java.util.function.Consumer<UpdateItemRequest.Builder> configure =
                                    call.getArgument(0);
                            var builder = UpdateItemRequest.builder();
                            configure.accept(builder);
                            var request = builder.build();
                            if (request.updateExpression().contains("published_at=:now")
                                    || request.updateExpression().contains("REMOVE lease_owner"))
                                throw new IllegalStateException(
                                        "database unavailable after SNS accepted event");
                            return dynamo.updateItem(request);
                        })
                .when(unstable)
                .updateItem(any(java.util.function.Consumer.class));
        var bodies = new java.util.ArrayList<String>();
        var sns = mock(SnsClient.class);
        doAnswer(
                        call -> {
                            java.util.function.Consumer<PublishRequest.Builder> configure =
                                    call.getArgument(0);
                            var builder = PublishRequest.builder();
                            configure.accept(builder);
                            bodies.add(builder.build().message());
                            return null;
                        })
                .when(sns)
                .publish(any(java.util.function.Consumer.class));
        new CheckoutOutboxPublisher(
                        unstable,
                        sns,
                        Clock.systemUTC(),
                        new SimpleMeterRegistry(),
                        "topic",
                        50,
                        2592000)
                .publishBatch();
        assertThat(
                        outbox.getItem(r -> r.key(k -> k.partitionValue(row.getEventId())))
                                .getPublishedAt())
                .isNull();
        new CheckoutOutboxPublisher(
                        dynamo,
                        sns,
                        Clock.offset(Clock.systemUTC(), java.time.Duration.ofSeconds(31)),
                        new SimpleMeterRegistry(),
                        "topic",
                        50,
                        2592000)
                .publishBatch();
        assertThat(bodies).containsExactly(row.getPayload(), row.getPayload());
        assertThat(
                        outbox.getItem(r -> r.key(k -> k.partitionValue(row.getEventId())))
                                .getPublishedAt())
                .isNotNull();
    }

    @Test
    void completionAndCancellationRaceProduceOneConsistentOutcome() throws Exception {
        var session = session();
        transitions.claim(session.getSessionId(), "ca_race", "digest");
        var active = transitions.get(session.getSessionId());
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var completion =
                    executor.submit(
                            () ->
                                    transitions.resolve(
                                            active,
                                            new PaymentClient.PaymentResult(
                                                    "pay_race", "CAPTURED", null, null)));
            var cancellation = executor.submit(() -> transitions.cancel(active));
            assertThat(completion.get()).isTrue();
            assertThat(cancellation.get()).isFalse();
        }
        assertThat(transitions.get(session.getSessionId()).getStatus()).isEqualTo("COMPLETED");
        assertThat(outbox.scan().items().stream().toList())
                .singleElement()
                .satisfies(row -> assertThat(row.getPayload()).contains("checkout.completed"));
    }

    private CheckoutSessionItem session() {
        var item = new CheckoutSessionItem();
        item.setSessionId("cs_" + UlidCreator.getUlid());
        item.setMerchantId("mer_test");
        item.setMerchantReference("order");
        item.setStatus("CREATED");
        item.setAmount(19900L);
        item.setCurrency("DKK");
        item.setCreatedAt(now());
        item.setUpdatedAt(now());
        item.setExpiresAt(now() + 1800);
        item.setDeleteAt(now() + 2592000);
        item.setSessionSecretHash("private-secret");
        item.setMetadata(Map.of("private", "data"));
        sessions.putItem(item);
        return item;
    }

    private long now() {
        return Instant.now().getEpochSecond();
    }
}
