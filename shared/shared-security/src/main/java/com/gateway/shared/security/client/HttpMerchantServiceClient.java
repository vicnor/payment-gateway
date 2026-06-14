package com.gateway.shared.security.client;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
public class HttpMerchantServiceClient implements MerchantServiceClient {

    private final RestClient restClient;

    public HttpMerchantServiceClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public List<ApiKeyCandidate> lookupCandidates(String prefix) {
        try {
            ApiKeyCandidatesResponse response =
                    restClient
                            .get()
                            .uri("/internal/v1/api-keys/{prefix}", prefix)
                            .retrieve()
                            .body(ApiKeyCandidatesResponse.class);
            if (response == null || response.keys() == null) {
                return List.of();
            }
            return response.keys();
        } catch (RestClientException e) {
            log.warn("Failed to contact merchant-service for prefix lookup [prefix={}]", prefix, e);
            throw new MerchantServiceUnavailableException(
                    "merchant-service unavailable during API key lookup", e);
        }
    }

    private record ApiKeyCandidatesResponse(List<ApiKeyCandidate> keys) {}
}
