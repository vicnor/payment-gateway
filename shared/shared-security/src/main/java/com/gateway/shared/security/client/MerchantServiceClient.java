package com.gateway.shared.security.client;

import java.util.List;

public interface MerchantServiceClient {

    /**
     * Returns all non-revoked API key candidates whose prefix matches the given value. Returns an
     * empty list when no match is found.
     */
    List<ApiKeyCandidate> lookupCandidates(String prefix);
}
