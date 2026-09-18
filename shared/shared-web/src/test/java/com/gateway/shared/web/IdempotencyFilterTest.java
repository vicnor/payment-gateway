package com.gateway.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.shared.web.idempotency.IdempotencyContext;
import com.gateway.shared.web.idempotency.IdempotencyFilter;
import com.gateway.shared.web.idempotency.IdempotencyStore;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class IdempotencyFilterTest {
    @Test
    void missingKeyReturnsValidationError() throws Exception {
        FakeStore store = new FakeStore(new IdempotencyStore.ClaimResult.Acquired());
        MockHttpServletResponse response = execute(store, null, "{}");
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("missing_idempotency_key");
    }

    @Test
    void replayReturnsCachedResponseAndHeader() throws Exception {
        var cached =
                new IdempotencyStore.CachedResponse(
                        201,
                        "application/json",
                        Map.of(),
                        "{\"id\":\"cs_original\"}".getBytes(StandardCharsets.UTF_8));
        FakeStore store = new FakeStore(new IdempotencyStore.ClaimResult.Replay(cached));
        MockHttpServletResponse response =
                execute(store, "550e8400-e29b-41d4-a716-446655440000", "{}");
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getHeader("Idempotent-Replay")).isEqualTo("true");
        assertThat(response.getContentAsString()).contains("cs_original");
    }

    @Test
    void successfulRequestIsCompleted() throws Exception {
        FakeStore store = new FakeStore(new IdempotencyStore.ClaimResult.Acquired());
        MockHttpServletRequest request =
                request("550e8400-e29b-41d4-a716-446655440000", "{\"amount\":100}");
        MockHttpServletResponse response = new MockHttpServletResponse();
        IdempotencyFilter filter = filter(store);
        filter.doFilter(
                request,
                response,
                (req, res) -> {
                    ((jakarta.servlet.http.HttpServletResponse) res).setStatus(201);
                    res.getWriter().write("{\"ok\":true}");
                });
        assertThat(store.completed).isTrue();
        assertThat(response.getContentAsString()).isEqualTo("{\"ok\":true}");
    }

    @Test
    void claimFailureReturnsStandardServiceUnavailableEnvelope() throws Exception {
        FakeStore store = new FakeStore(new IllegalStateException("store unavailable"));

        MockHttpServletResponse response =
                execute(store, "550e8400-e29b-41d4-a716-446655440000", "{}");

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString())
                .contains("\"type\":\"api_error\"")
                .contains("\"code\":\"idempotency_store_unavailable\"");
    }

    @Test
    void completionFailureReplacesSuccessfulBodyWithServiceUnavailableEnvelope() throws Exception {
        FakeStore store = new FakeStore(new IdempotencyStore.ClaimResult.Acquired());
        store.failComplete = true;
        MockHttpServletRequest request = request("550e8400-e29b-41d4-a716-446655440000", "{}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(store)
                .doFilter(
                        request,
                        response,
                        (req, res) -> {
                            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(201);
                            res.getWriter().write("{\"ok\":true}");
                        });

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString())
                .doesNotContain("\"ok\":true")
                .contains("idempotency_store_unavailable");
        assertThat(store.released).isTrue();
    }

    @Test
    void releaseFailureDoesNotReplaceBusinessError() throws Exception {
        FakeStore store = new FakeStore(new IdempotencyStore.ClaimResult.Acquired());
        store.failRelease = true;
        MockHttpServletRequest request = request("550e8400-e29b-41d4-a716-446655440000", "{}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(store)
                .doFilter(
                        request,
                        response,
                        (req, res) -> {
                            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(400);
                            res.getWriter().write("{\"error\":\"business\"}");
                        });

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"business\"}");
    }

    private static MockHttpServletResponse execute(FakeStore store, String key, String body)
            throws Exception {
        MockHttpServletRequest request = request(key, body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter(store).doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static MockHttpServletRequest request(String key, String body) {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/v1/checkout-sessions");
        request.setAttribute("merchant.principal", new Principal("mer_test"));
        if (key != null) request.addHeader("Idempotency-Key", key);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");
        return request;
    }

    private static IdempotencyFilter filter(FakeStore store) {
        return new IdempotencyFilter(
                store,
                new ObjectMapper(),
                Clock.fixed(Instant.ofEpochSecond(100), ZoneOffset.UTC),
                Duration.ofSeconds(60),
                Duration.ofHours(24));
    }

    public record Principal(String merchantId) {}

    private static final class FakeStore implements IdempotencyStore {
        private final ClaimResult result;
        private final RuntimeException claimFailure;
        private boolean completed;
        private boolean released;
        private boolean failComplete;
        private boolean failRelease;

        private FakeStore(ClaimResult result) {
            this.result = result;
            this.claimFailure = null;
        }

        private FakeStore(RuntimeException claimFailure) {
            this.result = null;
            this.claimFailure = claimFailure;
        }

        @Override
        public ClaimResult claim(Claim claim) {
            if (claimFailure != null) throw claimFailure;
            return result;
        }

        @Override
        public void complete(IdempotencyContext context, CachedResponse response) {
            if (failComplete) throw new IllegalStateException("completion unavailable");
            completed = true;
        }

        @Override
        public void release(IdempotencyContext context) {
            released = true;
            if (failRelease) throw new IllegalStateException("release unavailable");
        }
    }
}
