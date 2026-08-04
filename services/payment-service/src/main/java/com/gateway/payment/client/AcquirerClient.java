package com.gateway.payment.client;

import com.gateway.shared.web.error.AcquirerUnavailableException;

/**
 * Client for the acquirer's authorize endpoint.
 *
 * <p>In v1 the only acquirer is {@code test-acquirer-service}.
 */
public interface AcquirerClient {

    /**
     * Request authorization for a card transaction.
     *
     * <p>{@code cvv} may be {@code null} — in v1's single-use-token flow the CVV is not retained
     * after validation (PCI DSS). See ADR-0001 for the deferred real-acquirer CVV plan.
     *
     * @throws AcquirerUnavailableException if the acquirer times out (HTTP 504) or is unreachable
     */
    AcquireResult authorize(
            String pan,
            int expMonth,
            int expYear,
            String cvv,
            long amount,
            String currency,
            String reference);
}
