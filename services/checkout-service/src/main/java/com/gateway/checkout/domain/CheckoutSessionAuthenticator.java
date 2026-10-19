package com.gateway.checkout.domain;

import com.gateway.checkout.persistence.CheckoutSessionItem;
import com.gateway.shared.web.error.AuthenticationException;
import com.gateway.shared.web.error.GoneException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

@Service
public class CheckoutSessionAuthenticator {
    private final DynamoDbTable<CheckoutSessionItem> sessions;
    private final com.gateway.checkout.persistence.CheckoutTransitions transitions;

    public CheckoutSessionAuthenticator(
            @Qualifier("checkoutSessionsTable") DynamoDbTable<CheckoutSessionItem> sessions,
            com.gateway.checkout.persistence.CheckoutTransitions transitions) {
        this.transitions = transitions;
        this.sessions = sessions;
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
        item = transitions.expire(item);
        if (CheckoutSessionStatus.EXPIRED.name().equals(item.getStatus())) {
            throw new GoneException("checkout_session_expired", "Checkout session has expired.");
        }
        return item;
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
