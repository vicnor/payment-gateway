package com.gateway.payment.client;

import com.gateway.shared.web.error.ConflictException;
import com.gateway.shared.web.error.NotFoundException;

/**
 * Client for token-service's internal detokenize endpoint.
 *
 * <p>Callers should expect {@link NotFoundException} (token expired or not found) and {@link
 * ConflictException} (token already used — single-use constraint) to propagate unchanged to the
 * controller.
 */
public interface TokenServiceClient {

    /**
     * Detokenize the given token, mark it as used, and return card data.
     *
     * @throws NotFoundException if the token does not exist or has expired (404)
     * @throws ConflictException with code {@code token_already_used} if already consumed (409)
     * @throws com.gateway.shared.web.error.AcquirerUnavailableException if token-service is
     *     unreachable
     */
    DetokenizeData detokenize(String token);
}
