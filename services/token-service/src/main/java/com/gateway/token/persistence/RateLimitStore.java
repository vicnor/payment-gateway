package com.gateway.token.persistence;

import com.gateway.token.config.TokenProperties.RateLimitProperties;
import java.time.Clock;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/**
 * Atomic per-session token-attempt counter backed by DynamoDB.
 *
 * <p>Uses the low-level {@link DynamoDbClient} (not the enhanced client) because the enhanced
 * client does not support conditional {@code ADD} expressions. Each call to {@link
 * #tryAcquire(String)} atomically increments the {@code attempts} counter for the given {@code
 * session_id} and returns whether the attempt is within the configured limit.
 *
 * <p>The counter item carries its own TTL ({@code expires_at}) set on first write. This ensures
 * stale sessions are cleaned up automatically by DynamoDB without requiring a background job.
 *
 * <p>No card data ever enters this class — the PK is a {@code session_id}, which is an opaque
 * identifier that carries no PAN/CVV information.
 */
public class RateLimitStore {

    public static final String TABLE_NAME = "token_rate_limits";
    public static final String ATTR_SESSION_ID = "session_id";
    public static final String ATTR_ATTEMPTS = "attempts";
    public static final String ATTR_EXPIRES_AT = "expires_at";

    private final DynamoDbClient dynamoDbClient;
    private final RateLimitProperties rateLimitProperties;
    private final Clock clock;

    public RateLimitStore(
            DynamoDbClient dynamoDbClient, RateLimitProperties rateLimitProperties, Clock clock) {
        this.dynamoDbClient = dynamoDbClient;
        this.rateLimitProperties = rateLimitProperties;
        this.clock = clock;
    }

    /**
     * Atomically increments the attempt counter for the given session and checks the limit.
     *
     * <p>The first call for a session sets {@code attempts = 1} and {@code expires_at = now +
     * windowSeconds}. Subsequent calls within the window increment {@code attempts}. Once {@code
     * attempts >= maxAttempts} the condition fails and this method returns {@code false}.
     *
     * @param sessionId the checkout session identifier
     * @return {@code true} if the attempt is within the limit and should proceed; {@code false} if
     *     the limit is exceeded and the request should be rejected with 429
     */
    public boolean tryAcquire(String sessionId) {
        long ttl = clock.instant().getEpochSecond() + rateLimitProperties.windowSeconds();
        long max = rateLimitProperties.maxAttempts();

        UpdateItemRequest request =
                UpdateItemRequest.builder()
                        .tableName(TABLE_NAME)
                        .key(Map.of(ATTR_SESSION_ID, AttributeValue.fromS(sessionId)))
                        .updateExpression(
                                "ADD #attempts :one"
                                        + " SET #expires_at = if_not_exists(#expires_at, :ttl)")
                        .conditionExpression("attribute_not_exists(#attempts) OR #attempts < :max")
                        .expressionAttributeNames(
                                Map.of(
                                        "#attempts", ATTR_ATTEMPTS,
                                        "#expires_at", ATTR_EXPIRES_AT))
                        .expressionAttributeValues(
                                Map.of(
                                        ":one", AttributeValue.fromN("1"),
                                        ":ttl", AttributeValue.fromN(String.valueOf(ttl)),
                                        ":max", AttributeValue.fromN(String.valueOf(max))))
                        .returnValues(ReturnValue.NONE)
                        .build();

        try {
            dynamoDbClient.updateItem(request);
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }
}
