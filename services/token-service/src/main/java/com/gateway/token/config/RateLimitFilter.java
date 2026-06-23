package com.gateway.token.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.shared.web.error.ErrorEnvelope;
import com.gateway.token.persistence.RateLimitStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rate-limits {@code POST /checkout/{session_id}/tokens} to {@code maxAttempts} per session.
 *
 * <p>Every POST attempt counts — including those that later fail card validation or encounter a
 * server error. This is intentional: BIN-scraping attacks typically submit many structurally valid
 * but fraudulent PANs, and we want to cut them off regardless of the downstream outcome.
 *
 * <p>The counter is stored in DynamoDB ({@code token_rate_limits} table) so the limit is shared
 * across all running instances of the service. On limit breach a {@code 429} with the standard
 * error envelope is returned and a {@code Retry-After} header indicates when the window expires.
 *
 * <p>Filter order ({@code HIGHEST_PRECEDENCE + 2}): after {@link
 * com.gateway.shared.web.request.RequestIdFilter} (+1) so {@code MDC.requestId} is available for
 * the error envelope, and well before the controller.
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    // Path pattern: /checkout/<sessionId>/tokens
    private static final String PATH_PREFIX = "/checkout/";
    private static final String PATH_SUFFIX = "/tokens";

    private final RateLimitStore rateLimitStore;
    private final TokenProperties tokenProperties;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(
            RateLimitStore rateLimitStore,
            TokenProperties tokenProperties,
            ObjectMapper objectMapper) {
        this.rateLimitStore = rateLimitStore;
        this.tokenProperties = tokenProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * Only intercept {@code POST /checkout/{sessionId}/tokens}. All other paths (including {@code
     * OPTIONS} preflight and actuator) are skipped.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = resolvedPath(request);
        return !isTokenizePath(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String sessionId = extractSessionId(resolvedPath(request));
        if (!rateLimitStore.tryAcquire(sessionId)) {
            log.warn("Rate limit exceeded for session {}", sessionId);
            writeTooManyRequests(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private void writeTooManyRequests(HttpServletResponse response) throws IOException {
        ErrorEnvelope envelope =
                ErrorEnvelope.of(
                        "rate_limit_error",
                        "rate_limit_exceeded",
                        "Rate limit exceeded.",
                        null,
                        MDC.get("requestId"));
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(
                "Retry-After", String.valueOf(tokenProperties.rateLimit().windowSeconds()));
        objectMapper.writeValue(response.getWriter(), envelope);
    }

    /**
     * Returns true when path matches {@code /checkout/<non-empty-segment>/tokens} (exact — no
     * trailing slash or additional segments).
     */
    static boolean isTokenizePath(String path) {
        if (!path.startsWith(PATH_PREFIX) || !path.endsWith(PATH_SUFFIX)) {
            return false;
        }
        String middle = path.substring(PATH_PREFIX.length(), path.length() - PATH_SUFFIX.length());
        // middle must be non-empty and contain no slashes (exactly one path segment)
        return !middle.isEmpty() && !middle.contains("/");
    }

    /**
     * Extracts the {@code session_id} segment from a path that has already been validated by {@link
     * #isTokenizePath(String)}.
     */
    static String extractSessionId(String path) {
        return path.substring(PATH_PREFIX.length(), path.length() - PATH_SUFFIX.length());
    }

    private static String resolvedPath(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String uri = request.getRequestURI();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }
}
