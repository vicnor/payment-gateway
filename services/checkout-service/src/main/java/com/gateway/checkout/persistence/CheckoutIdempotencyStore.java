package com.gateway.checkout.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gateway.checkout.api.dto.CheckoutSessionResponse;
import com.gateway.checkout.config.AwsProperties;
import com.gateway.checkout.config.CheckoutProperties;
import com.gateway.shared.web.error.ConflictException;
import com.gateway.shared.web.error.ServiceUnavailableException;
import com.gateway.shared.web.idempotency.IdempotencyContext;
import com.gateway.shared.web.idempotency.IdempotencyStore;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactPutItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.KmsException;

@Component
public class CheckoutIdempotencyStore implements IdempotencyStore {
    private static final String PROCESSING = "PROCESSING";
    private static final String COMPLETED = "COMPLETED";
    private static final String SECRET_PLACEHOLDER = "__ENCRYPTED_SESSION_SECRET__";
    private final software.amazon.awssdk.services.dynamodb.DynamoDbClient dynamo;
    private final DynamoDbEnhancedClient enhanced;
    private final DynamoDbTable<CheckoutSessionItem> sessions;
    private final DynamoDbTable<MerchantReferenceItem> references;
    private final DynamoDbTable<IdempotencyKeyItem> keys;
    private final KmsClient kms;
    private final String kmsKeyId;
    private final ObjectMapper mapper;
    private final CheckoutProperties properties;

    public CheckoutIdempotencyStore(
            software.amazon.awssdk.services.dynamodb.DynamoDbClient dynamo,
            DynamoDbEnhancedClient enhanced,
            @Qualifier("checkoutSessionsTable") DynamoDbTable<CheckoutSessionItem> sessions,
            @Qualifier("checkoutMerchantReferencesTable")
                    DynamoDbTable<MerchantReferenceItem> references,
            @Qualifier("checkoutIdempotencyKeysTable") DynamoDbTable<IdempotencyKeyItem> keys,
            KmsClient kms,
            AwsProperties aws,
            ObjectMapper mapper,
            CheckoutProperties properties) {
        this.dynamo = dynamo;
        this.enhanced = enhanced;
        this.sessions = sessions;
        this.references = references;
        this.keys = keys;
        this.kms = kms;
        this.kmsKeyId = aws.kms().keyId();
        this.mapper = mapper;
        this.properties = properties;
    }

    @Override
    public ClaimResult claim(Claim claim) {
        Map<String, AttributeValue> item =
                Map.of(
                        "idempotency_key", s(claim.storageKey()),
                        "request_hash", s(claim.requestHash()),
                        "owner_token", s(claim.ownerToken()),
                        "status", s(PROCESSING),
                        "lease_until", n(claim.leaseUntilEpochSecond()),
                        "expires_at", n(claim.expiresAtEpochSecond()));
        try {
            dynamo.putItem(
                    PutItemRequest.builder()
                            .tableName(keys.tableName())
                            .item(item)
                            .conditionExpression("attribute_not_exists(idempotency_key)")
                            .build());
            return new ClaimResult.Acquired();
        } catch (ConditionalCheckFailedException ignored) {
            Map<String, AttributeValue> existing =
                    dynamo.getItem(
                                    GetItemRequest.builder()
                                            .tableName(keys.tableName())
                                            .key(Map.of("idempotency_key", s(claim.storageKey())))
                                            .consistentRead(true)
                                            .build())
                            .item();
            if (existing == null || existing.isEmpty()) return claim(claim);
            if (Long.parseLong(existing.get("expires_at").n()) <= claim.nowEpochSecond()) {
                try {
                    dynamo.deleteItem(
                            r ->
                                    r.tableName(keys.tableName())
                                            .key(Map.of("idempotency_key", s(claim.storageKey())))
                                            .conditionExpression("expires_at <= :now")
                                            .expressionAttributeValues(
                                                    Map.of(":now", n(claim.nowEpochSecond()))));
                    return claim(claim);
                } catch (ConditionalCheckFailedException race) {
                    return new ClaimResult.InProgress();
                }
            }
            if (!claim.requestHash().equals(existing.get("request_hash").s()))
                return new ClaimResult.Conflict();
            if (COMPLETED.equals(existing.get("status").s()))
                return new ClaimResult.Replay(replay(existing));
            long leaseUntil = Long.parseLong(existing.get("lease_until").n());
            if (leaseUntil >= claim.nowEpochSecond()) return new ClaimResult.InProgress();
            try {
                dynamo.updateItem(
                        UpdateItemRequest.builder()
                                .tableName(keys.tableName())
                                .key(Map.of("idempotency_key", s(claim.storageKey())))
                                .conditionExpression(
                                        "#status = :processing AND request_hash = :hash AND lease_until < :now")
                                .updateExpression(
                                        "SET owner_token=:owner, lease_until=:lease, expires_at=:expires")
                                .expressionAttributeNames(Map.of("#status", "status"))
                                .expressionAttributeValues(
                                        Map.of(
                                                ":processing",
                                                s(PROCESSING),
                                                ":hash",
                                                s(claim.requestHash()),
                                                ":now",
                                                n(claim.nowEpochSecond()),
                                                ":owner",
                                                s(claim.ownerToken()),
                                                ":lease",
                                                n(claim.leaseUntilEpochSecond()),
                                                ":expires",
                                                n(claim.expiresAtEpochSecond())))
                                .build());
                return new ClaimResult.Acquired();
            } catch (ConditionalCheckFailedException race) {
                return new ClaimResult.InProgress();
            }
        }
    }

    @Override
    public void complete(IdempotencyContext context, CachedResponse response) {
        ProtectedBody protectedBody = protectResponseBody(response.body(), context);
        Map<String, AttributeValue> values = new java.util.HashMap<>();
        values.put(":processing", s(PROCESSING));
        values.put(":completed", s(COMPLETED));
        values.put(":owner", s(context.ownerToken()));
        values.put(":hash", s(context.requestHash()));
        values.put(":rs", n(response.status()));
        values.put(
                ":ct",
                s(response.contentType() == null ? "application/json" : response.contentType()));
        values.put(":body", s(protectedBody.body()));
        String update =
                "SET #status=:completed, response_status=:rs, response_content_type=:ct, response_body=:body REMOVE lease_until";
        if (protectedBody.encryptedSecret() != null) {
            values.put(":secret", s(protectedBody.encryptedSecret()));
            update =
                    "SET #status=:completed, response_status=:rs, response_content_type=:ct, response_body=:body, encrypted_secret=:secret REMOVE lease_until";
        }
        try {
            dynamo.updateItem(
                    UpdateItemRequest.builder()
                            .tableName(keys.tableName())
                            .key(Map.of("idempotency_key", s(context.storageKey())))
                            .conditionExpression(
                                    "#status=:processing AND owner_token=:owner AND request_hash=:hash")
                            .updateExpression(update)
                            .expressionAttributeNames(Map.of("#status", "status"))
                            .expressionAttributeValues(values)
                            .build());
        } catch (DynamoDbException ex) {
            throw new ServiceUnavailableException(
                    "idempotency_store_unavailable",
                    "Idempotency storage is temporarily unavailable.");
        }
    }

    @Override
    public void release(IdempotencyContext context) {
        try {
            dynamo.deleteItem(
                    b ->
                            b.tableName(keys.tableName())
                                    .key(Map.of("idempotency_key", s(context.storageKey())))
                                    .conditionExpression(
                                            "#status=:processing AND owner_token=:owner")
                                    .expressionAttributeNames(Map.of("#status", "status"))
                                    .expressionAttributeValues(
                                            Map.of(
                                                    ":processing",
                                                    s(PROCESSING),
                                                    ":owner",
                                                    s(context.ownerToken()))));
        } catch (ConditionalCheckFailedException ignored) {
            // Another request completed or reclaimed it.
        }
    }

    public void completeCreate(
            IdempotencyContext context,
            CheckoutSessionItem session,
            MerchantReferenceItem reference,
            String secret,
            int responseStatus) {
        String encrypted = encrypt(secret, context);
        IdempotencyKeyItem completed =
                keys.getItem(
                        r ->
                                r.key(k -> k.partitionValue(context.storageKey()))
                                        .consistentRead(true));
        completed.setStatus(COMPLETED);
        completed.setLeaseUntil(null);
        completed.setResponseStatus(responseStatus);
        completed.setResponseContentType("application/json");
        completed.setEncryptedSecret(encrypted);
        completed.setSessionId(session.getSessionId());

        Expression absent =
                Expression.builder()
                        .expression("attribute_not_exists(#pk)")
                        .expressionNames(Map.of("#pk", "session_id"))
                        .build();
        Expression refAbsent =
                Expression.builder()
                        .expression("attribute_not_exists(#pk)")
                        .expressionNames(Map.of("#pk", "reference_key"))
                        .build();
        Expression ownsClaim =
                Expression.builder()
                        .expression(
                                "#status=:processing AND owner_token=:owner AND request_hash=:hash")
                        .expressionNames(Map.of("#status", "status"))
                        .expressionValues(
                                Map.of(
                                        ":processing",
                                        s(PROCESSING),
                                        ":owner",
                                        s(context.ownerToken()),
                                        ":hash",
                                        s(context.requestHash())))
                        .build();
        try {
            enhanced.transactWriteItems(
                    tx ->
                            tx.addPutItem(
                                            sessions,
                                            TransactPutItemEnhancedRequest.builder(
                                                            CheckoutSessionItem.class)
                                                    .item(session)
                                                    .conditionExpression(absent)
                                                    .build())
                                    .addPutItem(
                                            references,
                                            TransactPutItemEnhancedRequest.builder(
                                                            MerchantReferenceItem.class)
                                                    .item(reference)
                                                    .conditionExpression(refAbsent)
                                                    .build())
                                    .addPutItem(
                                            keys,
                                            TransactPutItemEnhancedRequest.builder(
                                                            IdempotencyKeyItem.class)
                                                    .item(completed)
                                                    .conditionExpression(ownsClaim)
                                                    .build()));
            context.markCompleted();
        } catch (TransactionCanceledException ex) {
            if (ex.cancellationReasons().size() > 1
                    && "ConditionalCheckFailed".equals(ex.cancellationReasons().get(1).code())) {
                throw new ConflictException(
                        "merchant_reference_exists",
                        "merchant_reference already exists for this merchant.");
            }
            throw new ServiceUnavailableException(
                    "checkout_store_unavailable", "Checkout storage is temporarily unavailable.");
        } catch (DynamoDbException ex) {
            throw new ServiceUnavailableException(
                    "checkout_store_unavailable", "Checkout storage is temporarily unavailable.");
        }
    }

    private CachedResponse replay(Map<String, AttributeValue> item) {
        int status = Integer.parseInt(item.get("response_status").n());
        String contentType = item.get("response_content_type").s();
        if (item.containsKey("response_body")) {
            String body = item.get("response_body").s();
            if (item.containsKey("encrypted_secret") && body.contains(SECRET_PLACEHOLDER)) {
                String secret =
                        decrypt(
                                item.get("encrypted_secret").s(),
                                item.get("idempotency_key").s(),
                                item.get("request_hash").s());
                body = body.replace(SECRET_PLACEHOLDER, secret);
            }
            return new CachedResponse(
                    status, contentType, Map.of(), body.getBytes(StandardCharsets.UTF_8));
        }
        String sessionId = item.get("session_id").s();
        CheckoutSessionItem session =
                sessions.getItem(r -> r.key(k -> k.partitionValue(sessionId)).consistentRead(true));
        String secret =
                decrypt(
                        item.get("encrypted_secret").s(),
                        item.get("idempotency_key").s(),
                        item.get("request_hash").s());
        try {
            byte[] body = mapper.writeValueAsBytes(toResponse(session, secret));
            return new CachedResponse(status, contentType, Map.of(), body);
        } catch (Exception ex) {
            throw new ServiceUnavailableException(
                    "idempotency_replay_unavailable", "The prior response could not be replayed.");
        }
    }

    private CheckoutSessionResponse toResponse(CheckoutSessionItem s, String secret) {
        String base = properties.baseUrl().replaceAll("/+$", "");
        String url = base + "/checkout/" + s.getSessionId() + "?k=" + secret;
        return new CheckoutSessionResponse(
                s.getSessionId(),
                "checkout_session",
                s.getStatus(),
                url,
                s.getAmount(),
                s.getCurrency(),
                s.getMerchantReference(),
                s.getExpiresAt(),
                s.getCreatedAt(),
                Boolean.TRUE.equals(s.getLivemode()),
                s.getPaymentId());
    }

    public String checkoutUrl(CheckoutSessionItem session) {
        if (session.getIdempotencyStorageKey() == null) return null;
        Map<String, AttributeValue> item =
                dynamo.getItem(
                                GetItemRequest.builder()
                                        .tableName(keys.tableName())
                                        .key(
                                                Map.of(
                                                        "idempotency_key",
                                                        s(session.getIdempotencyStorageKey())))
                                        .consistentRead(true)
                                        .build())
                        .item();
        if (item == null || !item.containsKey("encrypted_secret")) return null;
        String secret =
                decrypt(
                        item.get("encrypted_secret").s(),
                        item.get("idempotency_key").s(),
                        item.get("request_hash").s());
        return properties.baseUrl().replaceAll("/+$", "")
                + "/checkout/"
                + session.getSessionId()
                + "?k="
                + secret;
    }

    private String encrypt(String secret, IdempotencyContext context) {
        try {
            return Base64.getEncoder()
                    .encodeToString(
                            kms.encrypt(
                                            r ->
                                                    r.keyId(kmsKeyId)
                                                            .plaintext(
                                                                    SdkBytes.fromUtf8String(secret))
                                                            .encryptionContext(
                                                                    encryptionContext(
                                                                            context.storageKey(),
                                                                            context.requestHash())))
                                    .ciphertextBlob()
                                    .asByteArray());
        } catch (KmsException ex) {
            throw new ServiceUnavailableException(
                    "idempotency_encryption_unavailable",
                    "Checkout session creation is temporarily unavailable.");
        }
    }

    private ProtectedBody protectResponseBody(byte[] responseBody, IdempotencyContext context) {
        String raw = new String(responseBody, StandardCharsets.UTF_8);
        try {
            var node = mapper.readTree(raw);
            if (!(node instanceof ObjectNode object) || !object.hasNonNull("url")) {
                return new ProtectedBody(raw, null);
            }
            String url = object.get("url").asText();
            int marker = url.indexOf("?k=");
            if (marker < 0) return new ProtectedBody(raw, null);
            String secret = url.substring(marker + 3);
            object.put("url", url.substring(0, marker + 3) + SECRET_PLACEHOLDER);
            return new ProtectedBody(mapper.writeValueAsString(object), encrypt(secret, context));
        } catch (Exception ex) {
            throw new ServiceUnavailableException(
                    "idempotency_response_invalid",
                    "The successful response could not be cached safely.");
        }
    }

    private String decrypt(String encoded, String storageKey, String requestHash) {
        try {
            return kms.decrypt(
                            r ->
                                    r.keyId(kmsKeyId)
                                            .ciphertextBlob(
                                                    SdkBytes.fromByteArray(
                                                            Base64.getDecoder().decode(encoded)))
                                            .encryptionContext(
                                                    encryptionContext(storageKey, requestHash)))
                    .plaintext()
                    .asUtf8String();
        } catch (KmsException ex) {
            throw new ServiceUnavailableException(
                    "idempotency_decryption_unavailable",
                    "The prior response could not be replayed.");
        }
    }

    private static Map<String, String> encryptionContext(String key, String hash) {
        return Map.of(
                "purpose", "checkout-idempotency", "idempotency_key", key, "request_hash", hash);
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue n(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }

    private record ProtectedBody(String body, String encryptedSecret) {}
}
