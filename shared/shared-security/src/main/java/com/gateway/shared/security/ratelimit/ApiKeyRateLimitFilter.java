package com.gateway.shared.security.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.shared.security.MerchantPrincipal;
import com.gateway.shared.security.MerchantPrincipalHolder;
import com.gateway.shared.web.error.ErrorEnvelope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/** Applies the shared per-API-key limit to authenticated Merchant API requests. */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 6)
public final class ApiKeyRateLimitFilter extends OncePerRequestFilter {

    static final String LIMIT_HEADER = "RateLimit-Limit";
    static final String REMAINING_HEADER = "RateLimit-Remaining";
    static final String RESET_HEADER = "RateLimit-Reset";
    static final String RETRY_AFTER_HEADER = "Retry-After";

    private final ApiKeyRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final List<String> includePaths;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public ApiKeyRateLimitFilter(
            ApiKeyRateLimiter rateLimiter, ObjectMapper objectMapper, List<String> includePaths) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
        this.includePaths = List.copyOf(includePaths);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = resolvedPath(request);
        return includePaths.stream().noneMatch(pattern -> pathMatcher.match(pattern, path));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Object principalAttribute = request.getAttribute(MerchantPrincipalHolder.REQUEST_ATTR);
        if (!(principalAttribute instanceof MerchantPrincipal principal)) {
            chain.doFilter(request, response);
            return;
        }

        ApiKeyRateLimiter.Decision decision = rateLimiter.tryAcquire(principal.apiKeyId());
        response.setHeader(LIMIT_HEADER, String.valueOf(rateLimiter.capacity()));
        response.setHeader(REMAINING_HEADER, String.valueOf(decision.remaining()));
        response.setHeader(RESET_HEADER, String.valueOf(decision.resetSeconds()));

        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        response.setHeader(RETRY_AFTER_HEADER, String.valueOf(decision.retryAfterSeconds()));
        log.warn(
                "Merchant API rate limit exceeded [apiKeyId={}, requestId={}]",
                principal.apiKeyId(),
                MDC.get("requestId"));
        writeTooManyRequests(response);
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
        objectMapper.writeValue(response.getWriter(), envelope);
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
