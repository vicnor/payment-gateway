package com.gateway.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.shared.security.cache.ApiKeyCandidateCache;
import com.gateway.shared.security.client.ApiKeyCandidate;
import com.gateway.shared.security.client.MerchantServiceUnavailableException;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final ApiKeyCandidateCache cache;
    private final PasswordEncoder encoder;
    private final ObjectMapper objectMapper;
    private final List<String> skipPaths;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public ApiKeyAuthenticationFilter(
            ApiKeyCandidateCache cache,
            PasswordEncoder encoder,
            ObjectMapper objectMapper,
            List<String> skipPaths) {
        this.cache = cache;
        this.encoder = encoder;
        this.objectMapper = objectMapper;
        this.skipPaths = skipPaths;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = resolvedPath(request);
        return skipPaths.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private static String resolvedPath(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String uri = request.getRequestURI();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            writeUnauthorized(
                    response,
                    "missing_authorization",
                    "Authorization header with Bearer token is required.");
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length());
        if (!ApiKeyFormat.isValid(token)) {
            writeUnauthorized(response, "invalid_api_key", "Invalid API key.");
            return;
        }

        String prefix = ApiKeyFormat.extractPrefix(token);
        List<ApiKeyCandidate> candidates;
        try {
            candidates = cache.get(prefix);
        } catch (MerchantServiceUnavailableException e) {
            log.warn(
                    "merchant-service unavailable during auth [requestId={}]",
                    MDC.get("requestId"),
                    e);
            writeUnauthorized(response, "invalid_api_key", "Invalid API key.");
            return;
        }

        if (candidates.isEmpty()) {
            writeUnauthorized(response, "invalid_api_key", "Invalid API key.");
            return;
        }

        for (ApiKeyCandidate candidate : candidates) {
            if (encoder.matches(token, candidate.keyHash())) {
                MerchantPrincipal principal =
                        new MerchantPrincipal(
                                candidate.merchantId(),
                                candidate.id().toString(),
                                candidate.mode());
                MerchantPrincipalHolder.set(principal);
                request.setAttribute(MerchantPrincipalHolder.REQUEST_ATTR, principal);
                try {
                    chain.doFilter(request, response);
                } finally {
                    MerchantPrincipalHolder.clear();
                }
                return;
            }
        }

        writeUnauthorized(response, "invalid_api_key", "Invalid API key.");
    }

    private void writeUnauthorized(HttpServletResponse response, String code, String message)
            throws IOException {
        ErrorEnvelope envelope =
                ErrorEnvelope.of("authentication_error", code, message, null, MDC.get("requestId"));
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), envelope);
    }
}
