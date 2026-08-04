package com.gateway.payment.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.gateway.shared.web.error.AcquirerUnavailableException;
import com.gateway.shared.web.error.ConflictException;
import com.gateway.shared.web.error.NotFoundException;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

@WireMockTest
class HttpTokenServiceClientTest {

    private static final String TOKEN = "tok_testtoken0000000000000001";
    private static final String DETOKENIZE_URL = "/internal/v1/tokens/" + TOKEN + "/detokenize";

    HttpTokenServiceClient client;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wm) {
        // Mirror the production setup: SNAKE_CASE ObjectMapper + HTTP/1.1 request factory.
        // RestClient.builder() without a requestFactory defaults to the JDK HTTP client (HTTP/2)
        // which WireMock's plain-HTTP server does not support.
        ObjectMapper mapper =
                new ObjectMapper()
                        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(3000);
        requestFactory.setReadTimeout(10000);

        RestClient restClient =
                RestClient.builder()
                        .requestFactory(requestFactory)
                        .baseUrl(wm.getHttpBaseUrl())
                        .defaultHeader("X-Caller-Service", "payment-service")
                        .defaultHeader("X-Internal-Token", "test-secret")
                        .messageConverters(
                                converters -> {
                                    converters.removeIf(
                                            c -> c instanceof MappingJackson2HttpMessageConverter);
                                    converters.add(new MappingJackson2HttpMessageConverter(mapper));
                                })
                        .build();
        client = new HttpTokenServiceClient(restClient);
    }

    @Test
    void returnsCardDataOnSuccess() {
        stubFor(
                post(urlEqualTo(DETOKENIZE_URL))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader(
                                                "Content-Type", MediaType.APPLICATION_JSON_VALUE)
                                        .withBody(
                                                """
                                                {
                                                  "pan": "4242424242424242",
                                                  "exp_month": 12,
                                                  "exp_year": 2027,
                                                  "brand": "visa",
                                                  "last4": "4242",
                                                  "country": null,
                                                  "funding": null
                                                }
                                                """)));

        DetokenizeData result = client.detokenize(TOKEN);

        assertThat(result.pan()).isEqualTo("4242424242424242");
        assertThat(result.expMonth()).isEqualTo(12);
        assertThat(result.expYear()).isEqualTo(2027);
        assertThat(result.brand()).isEqualTo("visa");
        assertThat(result.last4()).isEqualTo("4242");
    }

    @Test
    void sendsInternalAuthHeaders() {
        stubFor(
                post(urlEqualTo(DETOKENIZE_URL))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader(
                                                "Content-Type", MediaType.APPLICATION_JSON_VALUE)
                                        .withBody(successBody())));

        client.detokenize(TOKEN);

        verify(
                postRequestedFor(urlEqualTo(DETOKENIZE_URL))
                        .withHeader("X-Caller-Service", equalTo("payment-service"))
                        .withHeader("X-Internal-Token", equalTo("test-secret")));
    }

    @Test
    void throws404OnNotFound() {
        stubFor(
                post(urlEqualTo(DETOKENIZE_URL))
                        .willReturn(
                                aResponse()
                                        .withStatus(404)
                                        .withHeader(
                                                "Content-Type", MediaType.APPLICATION_JSON_VALUE)
                                        .withBody(
                                                "{\"error\":{\"type\":\"not_found\","
                                                        + "\"code\":\"resource_not_found\","
                                                        + "\"message\":\"Token not found\"}}")));

        assertThatThrownBy(() -> client.detokenize(TOKEN)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void throws409OnAlreadyUsed() {
        stubFor(
                post(urlEqualTo(DETOKENIZE_URL))
                        .willReturn(
                                aResponse()
                                        .withStatus(409)
                                        .withHeader(
                                                "Content-Type", MediaType.APPLICATION_JSON_VALUE)
                                        .withBody(
                                                "{\"error\":{\"type\":\"conflict_error\","
                                                        + "\"code\":\"token_already_used\","
                                                        + "\"message\":\"Token has already been used.\"}}")));

        assertThatThrownBy(() -> client.detokenize(TOKEN))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo("token_already_used");
    }

    @Test
    void throwsUnavailableOnServerError() {
        stubFor(post(urlEqualTo(DETOKENIZE_URL)).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.detokenize(TOKEN))
                .isInstanceOf(AcquirerUnavailableException.class);
    }

    @Test
    void throwsUnavailableOnConnectionError() {
        WireMock.stubFor(
                post(urlEqualTo(DETOKENIZE_URL))
                        .willReturn(
                                aResponse()
                                        .withFault(
                                                com.github.tomakehurst.wiremock.http.Fault
                                                        .CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> client.detokenize(TOKEN))
                .isInstanceOf(AcquirerUnavailableException.class);
    }

    private static String successBody() {
        return """
               {"pan":"4242424242424242","exp_month":12,"exp_year":2027,
                "brand":"visa","last4":"4242","country":null,"funding":null}
               """;
    }
}
