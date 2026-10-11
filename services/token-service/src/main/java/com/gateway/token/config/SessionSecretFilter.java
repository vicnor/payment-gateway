package com.gateway.token.config;

import com.gateway.token.client.CheckoutSessionClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates the {@code X-Checkout-Session-Secret} header on browser-facing checkout requests.
 *
 * <p>When enabled, validation:
 *
 * <ol>
 *   <li>Extract {@code session_id} from the URL path.
 *   <li>Hashes the supplied secret with SHA-256.
 *   <li>Calls checkout-service's internal authentication endpoint with that digest.
 * </ol>
 */
@Component
@ConditionalOnProperty(name = "gateway.token.session-secret.enabled", havingValue = "true")
public class SessionSecretFilter extends OncePerRequestFilter {
    public static final String MERCHANT_ID_ATTRIBUTE = "checkout.merchant.id";
    private final CheckoutSessionClient checkoutSessions;

    public SessionSecretFilter(CheckoutSessionClient checkoutSessions) {
        this.checkoutSessions = checkoutSessions;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || !request.getRequestURI().matches(".*/checkout/[^/]+/tokens$");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String secret = request.getHeader("X-Checkout-Session-Secret");
        if (secret == null || secret.isBlank()) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json");
            response.getWriter()
                    .write(
                            """
                            {"error":{"type":"authentication_error","code":"missing_session_secret",\
                            "message":"X-Checkout-Session-Secret header is required."}}""");
            return;
        }
        String[] parts = request.getRequestURI().split("/");
        String sessionId = parts[parts.length - 2];
        try {
            var authenticated = checkoutSessions.authenticate(sessionId, sha256(secret));
            if (authenticated == null)
                throw new IllegalStateException("empty authentication response");
            request.setAttribute(MERCHANT_ID_ATTRIBUTE, authenticated.merchantId());
            chain.doFilter(request, response);
        } catch (RestClientResponseException ex) {
            response.setStatus(ex.getStatusCode().value());
            response.setContentType("application/json");
            response.getWriter().write(ex.getResponseBodyAsString());
        } catch (RuntimeException ex) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.setContentType("application/json");
            response.getWriter()
                    .write(
                            "{\"error\":{\"type\":\"api_error\",\"code\":\"checkout_service_unavailable\",\"message\":\"Checkout authentication is temporarily unavailable.\"}}");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
