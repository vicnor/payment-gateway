package com.gateway.token.client;

import org.springframework.web.client.RestClient;

public final class HttpCheckoutSessionClient implements CheckoutSessionClient {
    private final RestClient client;

    public HttpCheckoutSessionClient(RestClient client) {
        this.client = client;
    }

    @Override
    public AuthenticatedSession authenticate(String sessionId, String digest) {
        return client.get()
                .uri("/internal/v1/checkout-sessions/{id}/authenticate", sessionId)
                .header("X-Checkout-Session-Secret-SHA256", digest)
                .retrieve()
                .body(AuthenticatedSession.class);
    }
}
