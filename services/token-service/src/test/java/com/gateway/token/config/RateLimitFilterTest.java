package com.gateway.token.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.token.config.TokenProperties.CorsProperties;
import com.gateway.token.config.TokenProperties.InternalProperties;
import com.gateway.token.config.TokenProperties.RateLimitProperties;
import com.gateway.token.config.TokenProperties.SessionSecretProperties;
import com.gateway.token.persistence.RateLimitStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for {@link RateLimitFilter}.
 *
 * <p>Uses standalone servlet mocks rather than a Spring context to verify the filter's path
 * matching logic, rate-limit enforcement, and 429 response shape in isolation.
 */
class RateLimitFilterTest {

    private RateLimitStore rateLimitStore;
    private RateLimitFilter filter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        rateLimitStore = mock(RateLimitStore.class);
        objectMapper = new ObjectMapper();
        TokenProperties tokenProperties =
                new TokenProperties(
                        1800,
                        new CorsProperties(List.of("http://localhost:3000")),
                        new SessionSecretProperties(false),
                        new RateLimitProperties(100, 1800),
                        new InternalProperties(List.of()));
        filter = new RateLimitFilter(rateLimitStore, tokenProperties, objectMapper);
    }

    // --- path matching ---

    @Test
    void isTokenizePathMatchesExactPattern() {
        assertThat(RateLimitFilter.isTokenizePath("/checkout/cs_123/tokens")).isTrue();
    }

    @Test
    void isTokenizePathRejectsTrailingSlash() {
        assertThat(RateLimitFilter.isTokenizePath("/checkout/cs_123/tokens/")).isFalse();
    }

    @Test
    void isTokenizePathRejectsAdditionalSegment() {
        assertThat(RateLimitFilter.isTokenizePath("/checkout/cs_123/tokens/extra")).isFalse();
    }

    @Test
    void isTokenizePathRejectsEmptySessionId() {
        assertThat(RateLimitFilter.isTokenizePath("/checkout//tokens")).isFalse();
    }

    @Test
    void isTokenizePathRejectsDetokenizePath() {
        assertThat(RateLimitFilter.isTokenizePath("/internal/v1/tokens/tok_abc/detokenize"))
                .isFalse();
    }

    @Test
    void isTokenizePathRejectsActuator() {
        assertThat(RateLimitFilter.isTokenizePath("/actuator/health")).isFalse();
    }

    @Test
    void extractSessionIdReturnsMiddleSegment() {
        assertThat(RateLimitFilter.extractSessionId("/checkout/cs_session_01/tokens"))
                .isEqualTo("cs_session_01");
    }

    // --- shouldNotFilter ---

    @Test
    void shouldNotFilterReturnsTrueForOptionsMethod() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("OPTIONS", "/checkout/cs/tokens");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void shouldNotFilterReturnsTrueForActuatorPath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/actuator/health");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void shouldNotFilterReturnsFalseForPostToTokenizePath() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/checkout/cs_123/tokens");
        assertThat(filter.shouldNotFilter(request)).isFalse();
    }

    // --- filter execution ---

    @Test
    void withinLimitChainsToNextFilter() throws Exception {
        when(rateLimitStore.tryAcquire(eq("cs_allowed"))).thenReturn(true);

        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/checkout/cs_allowed/tokens");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void limitExceededReturns429WithErrorEnvelope() throws Exception {
        when(rateLimitStore.tryAcquire(eq("cs_blocked"))).thenReturn(false);

        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/checkout/cs_blocked/tokens");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).contains("application/json");
        assertThat(response.getContentAsString()).contains("\"type\":\"rate_limit_error\"");
        assertThat(response.getContentAsString()).contains("\"code\":\"rate_limit_exceeded\"");
        assertThat(response.getHeader("Retry-After")).isEqualTo("1800");
    }

    @Test
    void limitExceededDoesNotChainToNextFilter() throws Exception {
        when(rateLimitStore.tryAcquire(eq("cs_blocked2"))).thenReturn(false);

        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/checkout/cs_blocked2/tokens");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        // FilterChain must not be called when the limit is exceeded
        org.mockito.Mockito.verifyNoInteractions(chain);
    }
}
