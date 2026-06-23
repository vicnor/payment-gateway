package com.gateway.token.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies security response headers to every response from token-service.
 *
 * <p>This filter runs outermost (highest precedence) so that security headers are present on
 * <em>all</em> responses — including early-exit 429s from the rate-limit filter, 401/403 from
 * authentication filters, and 500s from the global exception handler.
 *
 * <p>Header rationale:
 *
 * <ul>
 *   <li>{@code Content-Security-Policy} — locked to {@code default-src 'none'} because
 *       token-service is a pure JSON API that never serves HTML or loads any resources. {@code
 *       frame-ancestors 'none'} is belt-and-suspenders with {@code X-Frame-Options}. {@code
 *       base-uri 'none'} prevents base-tag injection (irrelevant for JSON but harmless).
 *   <li>{@code X-Frame-Options: DENY} — stops the response being framed in legacy browsers that
 *       pre-date {@code frame-ancestors}.
 *   <li>{@code X-Content-Type-Options: nosniff} — prevents MIME-type sniffing that could cause a
 *       browser to treat a JSON response as something executable.
 *   <li>{@code Cache-Control: no-store} — ensures card-related responses are never cached by
 *       browsers or intermediary proxies. PCI-DSS requirement 6.
 *   <li>{@code Pragma: no-cache} — HTTP/1.0 equivalent of {@code Cache-Control: no-store} for
 *       legacy proxies.
 * </ul>
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    public static final String CSP_VALUE =
            "default-src 'none'; frame-ancestors 'none'; base-uri 'none'";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", CSP_VALUE);
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        chain.doFilter(request, response);
    }
}
