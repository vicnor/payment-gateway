package com.gateway.checkout.domain;

import com.gateway.checkout.api.dto.CheckoutSessionResponse;
import com.gateway.checkout.api.dto.CreateCheckoutSessionRequest;
import com.gateway.checkout.client.MerchantConfig;
import com.gateway.checkout.client.MerchantConfigClient;
import com.gateway.checkout.config.CheckoutProperties;
import com.gateway.checkout.persistence.BrandingAttribute;
import com.gateway.checkout.persistence.CheckoutIdempotencyStore;
import com.gateway.checkout.persistence.CheckoutSessionItem;
import com.gateway.checkout.persistence.CustomerAttribute;
import com.gateway.checkout.persistence.MerchantReferenceItem;
import com.gateway.shared.security.KeyMode;
import com.gateway.shared.security.MerchantPrincipal;
import com.gateway.shared.web.error.ConflictException;
import com.gateway.shared.web.error.NotFoundException;
import com.gateway.shared.web.error.PermissionException;
import com.gateway.shared.web.error.ServiceUnavailableException;
import com.gateway.shared.web.error.ValidationException;
import com.gateway.shared.web.idempotency.IdempotencyContext;
import com.github.f4b6a3.ulid.UlidCreator;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

@Service
public class CheckoutSessionService {
    private static final Set<String> CURRENCIES = Set.of("DKK", "EUR", "USD", "SEK", "NOK");
    private static final Set<String> LOCALES = Set.of("da-DK", "en-US");
    private final DynamoDbClient dynamo;
    private final DynamoDbTable<CheckoutSessionItem> sessions;
    private final MerchantConfigClient merchants;
    private final CheckoutIdempotencyStore idempotency;
    private final CheckoutProperties properties;
    private final SecureRandom random;
    private final Clock clock;

    public CheckoutSessionService(
            DynamoDbClient dynamo,
            @Qualifier("checkoutSessionsTable") DynamoDbTable<CheckoutSessionItem> sessions,
            MerchantConfigClient merchants,
            CheckoutIdempotencyStore idempotency,
            CheckoutProperties properties,
            SecureRandom random,
            Clock clock) {
        this.dynamo = dynamo;
        this.sessions = sessions;
        this.merchants = merchants;
        this.idempotency = idempotency;
        this.properties = properties;
        this.random = random;
        this.clock = clock;
    }

    public CheckoutSessionResponse create(
            CreateCheckoutSessionRequest request,
            MerchantPrincipal principal,
            IdempotencyContext context) {
        validateRequest(request);
        if (principal.keyMode() == KeyMode.LIVE) {
            throw new PermissionException(
                    "live_mode_unavailable", "Live-mode checkout is not available in v1.");
        }
        MerchantConfig merchant = merchants.get(principal.merchantId());
        if (!principal.keyMode().name().equalsIgnoreCase(merchant.mode())) {
            throw new ServiceUnavailableException(
                    "merchant_mode_mismatch", "Merchant configuration is inconsistent.");
        }
        if (!"ACTIVE".equalsIgnoreCase(merchant.status())) {
            throw new PermissionException(
                    "merchant_inactive", "The merchant account is not active.");
        }
        validateUrl(request.returnUrl(), merchant.returnUrlPattern(), false);
        validateUrl(request.cancelUrl(), merchant.cancelUrlPattern(), true);

        long now = clock.instant().getEpochSecond();
        String id = "cs_" + UlidCreator.getUlid().toString();
        byte[] secretBytes = new byte[32];
        random.nextBytes(secretBytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);

        CheckoutSessionItem item = new CheckoutSessionItem();
        item.setSessionId(id);
        item.setSessionSecretHash("sha256:" + sha256(secret));
        item.setIdempotencyStorageKey(context.storageKey());
        item.setMerchantId(principal.merchantId());
        item.setMerchantReference(request.merchantReference());
        item.setAmount(request.amount());
        item.setCurrency(request.currency());
        item.setStatus(CheckoutSessionStatus.CREATED.name());
        item.setReturnUrl(request.returnUrl());
        item.setCancelUrl(request.cancelUrl());
        item.setAvailablePaymentMethods(List.of("card"));
        item.setCustomer(customer(request.customer()));
        item.setDescription(request.description());
        item.setLocale(request.locale() == null ? "en-US" : request.locale());
        item.setBranding(branding(merchant.branding()));
        item.setMetadata(request.metadata() == null ? Map.of() : Map.copyOf(request.metadata()));
        item.setLivemode(false);
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        item.setExpiresAt(now + properties.sessionLifetime().toSeconds());
        item.setDeleteAt(now + properties.retention().toSeconds());

        MerchantReferenceItem reference = new MerchantReferenceItem();
        reference.setReferenceKey(
                sha256(principal.merchantId() + "\0" + request.merchantReference()));
        reference.setMerchantId(principal.merchantId());
        reference.setMerchantReference(request.merchantReference());
        reference.setSessionId(id);
        reference.setCreatedAt(now);
        idempotency.completeCreate(context, item, reference, secret, 201);
        return response(item, checkoutUrl(item, secret));
    }

    public CheckoutSessionResponse retrieve(String merchantId, String id) {
        validateId(id);
        CheckoutSessionItem item = findOwned(merchantId, id);
        item = expireIfNecessary(item);
        return response(item, idempotency.checkoutUrl(item));
    }

    public CheckoutSessionResponse cancel(String merchantId, String id) {
        validateId(id);
        CheckoutSessionItem item = expireIfNecessary(findOwned(merchantId, id));
        CheckoutSessionStatus status = CheckoutSessionStatus.valueOf(item.getStatus());
        if (status == CheckoutSessionStatus.CANCELLED)
            return response(item, idempotency.checkoutUrl(item));
        if (status != CheckoutSessionStatus.CREATED
                && status != CheckoutSessionStatus.IN_PROGRESS) {
            throw new ConflictException(
                    "invalid_checkout_session_state",
                    "Checkout session cannot be cancelled from status " + status + ".");
        }
        long now = clock.instant().getEpochSecond();
        try {
            dynamo.updateItem(
                    UpdateItemRequest.builder()
                            .tableName(sessions.tableName())
                            .key(Map.of("session_id", s(id)))
                            .conditionExpression(
                                    "merchant_id=:merchant AND (#status=:created OR #status=:progress) AND expires_at > :now")
                            .updateExpression("SET #status=:cancelled, updated_at=:now")
                            .expressionAttributeNames(Map.of("#status", "status"))
                            .expressionAttributeValues(
                                    Map.of(
                                            ":merchant",
                                            s(merchantId),
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
            CheckoutSessionItem current = expireIfNecessary(findOwned(merchantId, id));
            if (CheckoutSessionStatus.CANCELLED.name().equals(current.getStatus()))
                return response(current, idempotency.checkoutUrl(current));
            throw new ConflictException(
                    "invalid_checkout_session_state",
                    "Checkout session can no longer be cancelled.");
        }
        return response(findOwned(merchantId, id), idempotency.checkoutUrl(item));
    }

    private CheckoutSessionItem expireIfNecessary(CheckoutSessionItem item) {
        long now = clock.instant().getEpochSecond();
        CheckoutSessionStatus status = CheckoutSessionStatus.valueOf(item.getStatus());
        if (item.getExpiresAt() > now
                || (status != CheckoutSessionStatus.CREATED
                        && status != CheckoutSessionStatus.IN_PROGRESS)) return item;
        try {
            dynamo.updateItem(
                    UpdateItemRequest.builder()
                            .tableName(sessions.tableName())
                            .key(Map.of("session_id", s(item.getSessionId())))
                            .conditionExpression(
                                    "(#status=:created OR #status=:progress) AND expires_at <= :now")
                            .updateExpression("SET #status=:expired, updated_at=:now")
                            .expressionAttributeNames(Map.of("#status", "status"))
                            .expressionAttributeValues(
                                    Map.of(
                                            ":created",
                                            s("CREATED"),
                                            ":progress",
                                            s("IN_PROGRESS"),
                                            ":expired",
                                            s("EXPIRED"),
                                            ":now",
                                            n(now)))
                            .build());
        } catch (ConditionalCheckFailedException ignored) {
            // A concurrent terminal transition won; return its current state.
        }
        return sessions.getItem(Key.builder().partitionValue(item.getSessionId()).build());
    }

    private CheckoutSessionItem findOwned(String merchantId, String id) {
        CheckoutSessionItem item =
                sessions.getItem(r -> r.key(k -> k.partitionValue(id)).consistentRead(true));
        if (item == null || !merchantId.equals(item.getMerchantId()))
            throw new NotFoundException("Checkout session not found.");
        return item;
    }

    private void validateRequest(CreateCheckoutSessionRequest request) {
        if (request.amount() < 100 || request.amount() > properties.maximumAmount())
            throw validation(
                    "invalid_amount",
                    "amount must be between 100 and " + properties.maximumAmount(),
                    "amount");
        if (!CURRENCIES.contains(request.currency()))
            throw validation(
                    "invalid_currency",
                    "currency must be one of DKK, EUR, USD, SEK, NOK",
                    "currency");
        rejectWhitespace(request.merchantReference(), "merchant_reference");
        if (request.merchantReference().chars().anyMatch(Character::isISOControl))
            throw validation(
                    "invalid_merchant_reference",
                    "merchant_reference must not contain control characters",
                    "merchant_reference");
        if (request.locale() != null && !LOCALES.contains(request.locale()))
            throw validation("invalid_locale", "locale must be da-DK or en-US", "locale");
        rejectOptionalBlank(request.description(), "description");
        if (request.customer() != null) {
            rejectOptionalBlank(request.customer().email(), "customer.email");
            rejectOptionalBlank(request.customer().reference(), "customer.reference");
        }
        if (request.metadata() != null)
            request.metadata()
                    .forEach(
                            (k, v) -> {
                                if (k == null || k.isBlank() || k.length() > 40)
                                    throw validation(
                                            "invalid_metadata",
                                            "metadata keys must contain 1 to 40 characters",
                                            "metadata");
                                if (v == null || v.isBlank() || v.length() > 500)
                                    throw validation(
                                            "invalid_metadata",
                                            "metadata values must contain 1 to 500 characters",
                                            "metadata");
                            });
    }

    private void validateUrl(String value, String regex, boolean cancel) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException ex) {
            throw validation(
                    "invalid_url",
                    "URL must be a valid absolute URI",
                    cancel ? "cancel_url" : "return_url");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (!uri.isAbsolute() || host == null || uri.getUserInfo() != null)
            throw validation(
                    "invalid_url",
                    "URL must be absolute and must not contain user-info",
                    cancel ? "cancel_url" : "return_url");
        boolean localHttp =
                "http".equalsIgnoreCase(scheme)
                        && (host.equalsIgnoreCase("localhost")
                                || host.equalsIgnoreCase("host.docker.internal")
                                || host.equals("127.0.0.1")
                                || host.equals("::1"));
        if (!"https".equalsIgnoreCase(scheme) && !localHttp)
            throw validation(
                    "invalid_url",
                    "URL must use HTTPS (local HTTP is allowed for test merchants)",
                    cancel ? "cancel_url" : "return_url");
        if (regex == null || !Pattern.compile(regex).matcher(value).matches())
            throw validation(
                    "url_not_allowed",
                    "URL does not match the merchant's allowed pattern",
                    cancel ? "cancel_url" : "return_url");
    }

    private static void validateId(String id) {
        if (id == null || !id.matches("cs_[0-9A-HJKMNP-TV-Z]{26}"))
            throw new NotFoundException("Checkout session not found.");
    }

    private static void rejectWhitespace(String value, String param) {
        if (!value.equals(value.strip()))
            throw validation(
                    "invalid_" + param,
                    param + " must not have leading or trailing whitespace",
                    param);
    }

    private static void rejectOptionalBlank(String value, String param) {
        if (value != null && value.isBlank())
            throw validation(
                    "invalid_" + param.replace('.', '_'), param + " must not be blank", param);
    }

    private static ValidationException validation(String code, String message, String param) {
        return new ValidationException(code, message, param);
    }

    private static CustomerAttribute customer(CreateCheckoutSessionRequest.Customer source) {
        if (source == null) return null;
        CustomerAttribute out = new CustomerAttribute();
        out.setEmail(source.email());
        out.setReference(source.reference());
        return out;
    }

    private static BrandingAttribute branding(MerchantConfig.Branding source) {
        BrandingAttribute out = new BrandingAttribute();
        if (source != null) {
            out.setLogoUrl(source.logoUrl());
            out.setAccentColor(source.accentColor());
        }
        return out;
    }

    private CheckoutSessionResponse response(CheckoutSessionItem s, String url) {
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
                s.getPaymentId(),
                null);
    }

    private String checkoutUrl(CheckoutSessionItem item, String secret) {
        return properties.baseUrl().replaceAll("/+$", "")
                + "/checkout/"
                + item.getSessionId()
                + "?k="
                + secret;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue n(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }
}
