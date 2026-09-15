package com.gateway.shared.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.shared.security.KeyMode;
import com.gateway.shared.security.MerchantPrincipal;
import com.gateway.shared.security.MerchantPrincipalHolder;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiKeyRateLimitFilterTest {

    private final MutableTicker ticker = new MutableTicker();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ApiKeyRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        ApiKeyRateLimiter limiter = new ApiKeyRateLimiter(1, 2, Duration.ofMinutes(10), ticker);
        filter = new ApiKeyRateLimitFilter(limiter, objectMapper, List.of("/v1/**"));
    }

    @Test
    void addsHeadersToSuccessfulAuthenticatedRequest() throws Exception {
        MockHttpServletRequest request = authenticatedRequest("/v1/payments", "key-a");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {});

        assertThat(response.getHeader("RateLimit-Limit")).isEqualTo("2");
        assertThat(response.getHeader("RateLimit-Remaining")).isEqualTo("1");
        assertThat(response.getHeader("RateLimit-Reset")).isEqualTo("1");
        assertThat(response.getHeader("Retry-After")).isNull();
    }

    @Test
    void retainsHeadersWhenDownstreamReturnsError() throws Exception {
        MockHttpServletRequest request = authenticatedRequest("/v1/payments/missing", "key-a");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(404));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getHeader("RateLimit-Limit")).isEqualTo("2");
        assertThat(response.getHeader("RateLimit-Remaining")).isEqualTo("1");
    }

    @Test
    void exhaustionReturnsStandardEnvelopeAndDoesNotInvokeDownstream() throws Exception {
        MockHttpServletRequest request = authenticatedRequest("/v1/payments", "key-a");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicLong downstreamCalls = new AtomicLong();
        MDC.put("requestId", "req_test");
        try {
            filter.doFilter(request, response, (req, res) -> downstreamCalls.incrementAndGet());
        } finally {
            MDC.clear();
        }

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("RateLimit-Limit")).isEqualTo("2");
        assertThat(response.getHeader("RateLimit-Remaining")).isEqualTo("0");
        assertThat(response.getHeader("RateLimit-Reset")).isEqualTo("2");
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(downstreamCalls).hasValue(0);
        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertThat(body.path("error").path("type").asText()).isEqualTo("rate_limit_error");
        assertThat(body.path("error").path("code").asText()).isEqualTo("rate_limit_exceeded");
        assertThat(body.path("error").path("request_id").asText()).isEqualTo("req_test");
    }

    @Test
    void skipsNonMerchantPathsAndRequestsWithoutAuthenticatedPrincipal() throws Exception {
        for (String path : List.of("/internal/v1/payments", "/actuator/health", "/admin/jobs")) {
            MockHttpServletRequest request = authenticatedRequest(path, "key-a");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> {});
            assertThat(response.getHeader("RateLimit-Limit")).isNull();
        }

        MockHttpServletRequest unauthenticated = new MockHttpServletRequest("GET", "/v1/payments");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(unauthenticated, response, (req, res) -> {});
        assertThat(response.getHeader("RateLimit-Limit")).isNull();
    }

    @Test
    void isolatesFilterQuotaByApiKeyId() throws Exception {
        MockHttpServletRequest firstKey = authenticatedRequest("/v1/payments", "key-a");
        filter.doFilter(firstKey, new MockHttpServletResponse(), (req, res) -> {});
        filter.doFilter(firstKey, new MockHttpServletResponse(), (req, res) -> {});

        MockHttpServletResponse otherKeyResponse = new MockHttpServletResponse();
        filter.doFilter(
                authenticatedRequest("/v1/payments", "key-b"), otherKeyResponse, (req, res) -> {});

        assertThat(otherKeyResponse.getStatus()).isEqualTo(200);
        assertThat(otherKeyResponse.getHeader("RateLimit-Remaining")).isEqualTo("1");
    }

    private static MockHttpServletRequest authenticatedRequest(String path, String apiKeyId) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setAttribute(
                MerchantPrincipalHolder.REQUEST_ATTR,
                new MerchantPrincipal("mer_test", apiKeyId, KeyMode.TEST));
        return request;
    }

    private static final class MutableTicker implements Ticker {

        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }
    }
}
