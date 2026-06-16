package com.gateway.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.shared.web.error.ErrorEnvelope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates service-to-service calls on {@code /internal/**} paths.
 *
 * <p>Each caller must supply two headers:
 *
 * <ul>
 *   <li>{@code X-Caller-Service} — the caller's registered service identifier
 *   <li>{@code X-Internal-Token} — the pre-shared secret for that caller
 * </ul>
 *
 * <p>The secret is compared using a constant-time equality check to prevent timing attacks. On
 * success the caller id is stored as request attribute {@link #CALLER_ID_ATTR} for downstream use
 * (audit logging, etc.). On failure a 403 with the standard error envelope is returned and the
 * request is not forwarded.
 *
 * <p>All non-{@code /internal/} paths are skipped — this filter is a no-op for public endpoints.
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 4)
public class InternalCallerAuthenticationFilter extends OncePerRequestFilter {

    public static final String CALLER_ID_ATTR = "internal.caller.id";

    static final String HEADER_CALLER_SERVICE = "X-Caller-Service";
    static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";

    private final List<CallerConfig> allowedCallers;
    private final ObjectMapper objectMapper;

    public InternalCallerAuthenticationFilter(
            List<CallerConfig> allowedCallers, ObjectMapper objectMapper) {
        this.allowedCallers = allowedCallers;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !resolvedPath(request).startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String callerId = request.getHeader(HEADER_CALLER_SERVICE);
        String callerToken = request.getHeader(HEADER_INTERNAL_TOKEN);

        if (callerId == null || callerToken == null) {
            writeForbidden(response, "Missing required internal service headers.");
            return;
        }

        CallerConfig match = null;
        for (CallerConfig caller : allowedCallers) {
            if (caller.id().equals(callerId)) {
                match = caller;
                break;
            }
        }

        if (match == null || !constantTimeEquals(callerToken, match.secret())) {
            writeForbidden(response, "Internal service authentication failed.");
            return;
        }

        request.setAttribute(CALLER_ID_ATTR, callerId);
        chain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private void writeForbidden(HttpServletResponse response, String message) throws IOException {
        ErrorEnvelope envelope =
                ErrorEnvelope.of(
                        "permission_error", "forbidden", message, null, MDC.get("requestId"));
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
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
