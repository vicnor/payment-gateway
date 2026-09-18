package com.gateway.checkout.client;

import com.gateway.shared.web.error.ServiceUnavailableException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public final class HttpMerchantConfigClient implements MerchantConfigClient {
    private final RestClient client;

    public HttpMerchantConfigClient(RestClient client) {
        this.client = client;
    }

    @Override
    public MerchantConfig get(String merchantId) {
        try {
            MerchantConfig config =
                    client.get()
                            .uri("/internal/v1/merchants/{id}", merchantId)
                            .retrieve()
                            .body(MerchantConfig.class);
            if (config == null) throw new RestClientException("empty merchant response");
            return config;
        } catch (RestClientException ex) {
            throw new ServiceUnavailableException(
                    "merchant_service_unavailable",
                    "Merchant configuration is temporarily unavailable.");
        }
    }
}
