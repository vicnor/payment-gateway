package com.gateway.token.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates the {@code X-Checkout-Session-Secret} header on browser-facing checkout requests.
 *
 * <p>This filter is <strong>disabled by default</strong> ({@code
 * gateway.token.session-secret.enabled: false}) and only activated once checkout-service (Phase 5)
 * is built. When enabled, real validation must:
 *
 * <ol>
 *   <li>Extract {@code session_id} from the URL path.
 *   <li>Call checkout-service to fetch {@code session_secret_hash}.
 *   <li>Assert {@code SHA256(header) == session_secret_hash} with constant-time comparison.
 * </ol>
 *
 * <p>TODO (Phase 5): inject a {@code CheckoutServiceClient} and implement the above.
 */
@Component
@ConditionalOnProperty(name = "gateway.token.session-secret.enabled", havingValue = "true")
public class SessionSecretFilter extends OncePerRequestFilter {

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
        chain.doFilter(request, response);
    }
}
