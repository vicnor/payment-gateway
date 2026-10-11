package com.gateway.checkout.api;

import com.gateway.checkout.domain.CheckoutSessionAuthenticator;
import com.gateway.checkout.persistence.CheckoutSessionItem;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/checkout-sessions")
public class CheckoutSessionInternalController {
    private final CheckoutSessionAuthenticator authenticator;

    public CheckoutSessionInternalController(CheckoutSessionAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @GetMapping("/{id}/authenticate")
    public AuthenticationResponse authenticate(
            @PathVariable String id,
            @RequestHeader("X-Checkout-Session-Secret-SHA256") String digest) {
        CheckoutSessionItem item = authenticator.authenticateDigest(id, digest);
        return new AuthenticationResponse(
                item.getSessionId(), item.getMerchantId(), item.getExpiresAt());
    }

    public record AuthenticationResponse(String sessionId, String merchantId, long expiresAt) {}
}
