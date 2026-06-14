package com.gateway.shared.security.cache;

import com.gateway.shared.security.client.ApiKeyCandidate;
import com.gateway.shared.security.client.MerchantServiceClient;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import java.time.Duration;
import java.util.List;

public class ApiKeyCandidateCache {

    private final LoadingCache<String, List<ApiKeyCandidate>> cache;

    public ApiKeyCandidateCache(MerchantServiceClient client, Duration ttl) {
        this.cache =
                Caffeine.newBuilder()
                        .expireAfterWrite(ttl)
                        .maximumSize(10_000)
                        .build(client::lookupCandidates);
    }

    /**
     * Returns candidates for the given prefix. Empty list is cached as a negative entry. Throws
     * {@link com.gateway.shared.security.client.MerchantServiceUnavailableException} if the
     * upstream call fails; Caffeine does not cache failed loads.
     */
    public List<ApiKeyCandidate> get(String prefix) {
        return cache.get(prefix);
    }
}
