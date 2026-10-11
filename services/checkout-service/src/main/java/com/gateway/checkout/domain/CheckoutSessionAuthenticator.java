package com.gateway.checkout.domain;

import com.gateway.checkout.persistence.CheckoutSessionItem;
import com.gateway.shared.web.error.AuthenticationException;
import com.gateway.shared.web.error.GoneException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

@Service
public class CheckoutSessionAuthenticator {
    private final DynamoDbTable<CheckoutSessionItem> sessions;
    private final DynamoDbClient dynamo;
    private final Clock clock;

    public CheckoutSessionAuthenticator(
            @Qualifier("checkoutSessionsTable") DynamoDbTable<CheckoutSessionItem> sessions,
            DynamoDbClient dynamo,
            Clock clock) {
        this.sessions = sessions;
        this.dynamo = dynamo;
        this.clock = clock;
    }

    public CheckoutSessionItem authenticate(String id, String secret) {
        if (secret == null || secret.isBlank()) throw unauthenticated();
        return authenticateDigest(id, sha256(secret));
    }

    public CheckoutSessionItem authenticateDigest(String id, String digest) {
        if (id == null || !id.matches("cs_[0-9A-HJKMNP-TV-Z]{26}") || digest == null)
            throw unauthenticated();
        CheckoutSessionItem item =
                sessions.getItem(r -> r.key(k -> k.partitionValue(id)).consistentRead(true));
        if (item == null || item.getSessionSecretHash() == null) throw unauthenticated();
        String expected = item.getSessionSecretHash().replaceFirst("^sha256:", "");
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                digest.getBytes(StandardCharsets.US_ASCII))) throw unauthenticated();
        if (item.getExpiresAt() <= clock.instant().getEpochSecond()
                || CheckoutSessionStatus.EXPIRED.name().equals(item.getStatus())) {
            markExpired(item);
            throw new GoneException("checkout_session_expired", "Checkout session has expired.");
        }
        return item;
    }

    private void markExpired(CheckoutSessionItem item) {
        if (CheckoutSessionStatus.EXPIRED.name().equals(item.getStatus())) return;
        long now = clock.instant().getEpochSecond();
        try {
            dynamo.updateItem(
                    UpdateItemRequest.builder()
                            .tableName(sessions.tableName())
                            .key(Map.of("session_id", AttributeValue.fromS(item.getSessionId())))
                            .conditionExpression(
                                    "(#s=:created OR #s=:progress) AND expires_at<=:now")
                            .updateExpression("SET #s=:expired, updated_at=:now")
                            .expressionAttributeNames(Map.of("#s", "status"))
                            .expressionAttributeValues(
                                    Map.of(
                                            ":created", AttributeValue.fromS("CREATED"),
                                            ":progress", AttributeValue.fromS("IN_PROGRESS"),
                                            ":expired", AttributeValue.fromS("EXPIRED"),
                                            ":now", AttributeValue.fromN(Long.toString(now))))
                            .build());
        } catch (ConditionalCheckFailedException ignored) {
            // A terminal transition won the race.
        }
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static AuthenticationException unauthenticated() {
        return new AuthenticationException("Checkout session authentication failed.");
    }
}
