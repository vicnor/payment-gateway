package com.gateway.shared.security;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.shared.security.client.ApiKeyCandidate;
import com.gateway.shared.security.client.HttpMerchantServiceClient;
import com.gateway.shared.security.client.MerchantServiceUnavailableException;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

@WireMockTest
class HttpMerchantServiceClientTest {

    HttpMerchantServiceClient client;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmRuntimeInfo) {
        RestClient restClient =
                RestClient.builder().baseUrl(wmRuntimeInfo.getHttpBaseUrl()).build();
        client = new HttpMerchantServiceClient(restClient);
    }

    @Test
    void returnsCandidatesForKnownPrefix(WireMockRuntimeInfo ignored) throws Exception {
        String prefix = "sk_test_01JTESTA";
        stubFor(
                get(urlEqualTo("/internal/v1/api-keys/" + prefix))
                        .willReturn(
                                aResponse()
                                        .withHeader(
                                                "Content-Type", MediaType.APPLICATION_JSON_VALUE)
                                        .withBody(
                                                """
                                                {
                                                  "keys": [{
                                                    "id": "a0000000-0000-0000-0000-000000000001",
                                                    "merchantId": "mer_abc123",
                                                    "keyPrefix": "sk_test_01JTESTA",
                                                    "keyHash": "$argon2id$fake$hash",
                                                    "mode": "TEST"
                                                  }]
                                                }
                                                """)));

        List<ApiKeyCandidate> candidates = client.lookupCandidates(prefix);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().merchantId()).isEqualTo("mer_abc123");
        assertThat(candidates.getFirst().mode()).isEqualTo(KeyMode.TEST);
        assertThat(candidates.getFirst().keyPrefix()).isEqualTo("sk_test_01JTESTA");
    }

    @Test
    void returnsEmptyListWhenNoMatch() {
        String prefix = "sk_test_unknown0";
        stubFor(
                get(urlEqualTo("/internal/v1/api-keys/" + prefix))
                        .willReturn(
                                aResponse()
                                        .withHeader(
                                                "Content-Type", MediaType.APPLICATION_JSON_VALUE)
                                        .withBody("{\"keys\": []}")));

        List<ApiKeyCandidate> candidates = client.lookupCandidates(prefix);

        assertThat(candidates).isEmpty();
    }

    @Test
    void throwsUnavailableOnServerError() {
        String prefix = "sk_test_errorpfx";
        stubFor(
                get(urlEqualTo("/internal/v1/api-keys/" + prefix))
                        .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.lookupCandidates(prefix))
                .isInstanceOf(MerchantServiceUnavailableException.class);
    }
}
