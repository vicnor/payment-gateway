package com.gateway.payment.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
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
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

@WireMockTest
class HttpAcquirerClientTest {

    private static final String AUTHORIZE_URL = "/internal/v1/authorize";

    HttpAcquirerClient client;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wm) {
        // Mirror production setup: SNAKE_CASE ObjectMapper + HTTP/1.1 request factory.
        // RestClient.builder() without a requestFactory defaults to the JDK HTTP client (HTTP/2)
        // which WireMock's plain-HTTP server does not support.
        ObjectMapper mapper =
                new ObjectMapper()
                        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(3000);
        requestFactory.setReadTimeout(35000);

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
        client = new HttpAcquirerClient(restClient);
    }

    @Test
    void approvedResponseMapsToApprovedResult() {
        stubFor(
                post(urlEqualTo(AUTHORIZE_URL))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader(
                                                "Content-Type", MediaType.APPLICATION_JSON_VALUE)
                                        .withBody(
                                                """
                                                {
                                                  "outcome": "APPROVED",
                                                  "auth_code": "A1B2C3D4",
                                                  "acquirer_reference": "acq_test_ref01"
                                                }
                                                """)));

        AcquireResult result =
                client.authorize("4242424242424242", 12, 2027, null, 19900L, "DKK", "ref_test");

        assertThat(result.outcome()).isEqualTo(AcquirerOutcome.APPROVED);
        assertThat(result.authCode()).isEqualTo("A1B2C3D4");
        assertThat(result.acquirerReference()).isEqualTo("acq_test_ref01");
        assertThat(result.errorCode()).isNull();
    }

    @Test
    void declinedResponseMapsToDeclinedResult() {
        stubFor(
                post(urlEqualTo(AUTHORIZE_URL))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader(
                                                "Content-Type", MediaType.APPLICATION_JSON_VALUE)
                                        .withBody(
                                                """
                                                {
                                                  "outcome": "DECLINED",
                                                  "error_code": "card_declined"
                                                }
                                                """)));

        AcquireResult result =
                client.authorize("4000000000000002", 12, 2027, null, 19900L, "DKK", "ref_test");

        assertThat(result.outcome()).isEqualTo(AcquirerOutcome.DECLINED);
        assertThat(result.errorCode()).isEqualTo("card_declined");
        assertThat(result.authCode()).isNull();
    }

    @Test
    void gatewayTimeoutThrowsAcquirerUnavailable() {
        stubFor(
                post(urlEqualTo(AUTHORIZE_URL))
                        .willReturn(
                                aResponse()
                                        .withStatus(504)
                                        .withHeader(
                                                "Content-Type", MediaType.APPLICATION_JSON_VALUE)
                                        .withBody(
                                                "{\"outcome\":\"ERROR\","
                                                        + "\"error_code\":\"acquirer_timeout\"}")));

        assertThatThrownBy(
                        () ->
                                client.authorize(
                                        "4000000000000341",
                                        12,
                                        2027,
                                        null,
                                        19900L,
                                        "DKK",
                                        "ref_timeout"))
                .isInstanceOf(AcquirerUnavailableException.class);
    }

    @Test
    void sendsInternalAuthHeaders() {
        stubFor(
                post(urlEqualTo(AUTHORIZE_URL))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader(
                                                "Content-Type", MediaType.APPLICATION_JSON_VALUE)
                                        .withBody(
                                                "{\"outcome\":\"APPROVED\","
                                                        + "\"auth_code\":\"X\","
                                                        + "\"acquirer_reference\":\"acq_y\"}")));

        client.authorize("4242424242424242", 12, 2027, null, 100L, "DKK", "ref_headers");

        verify(
                postRequestedFor(urlEqualTo(AUTHORIZE_URL))
                        .withHeader("X-Caller-Service", equalTo("payment-service"))
                        .withHeader("X-Internal-Token", equalTo("test-secret")));
    }

    @Test
    void requestBodyIncludesRequiredFields() {
        stubFor(
                post(urlEqualTo(AUTHORIZE_URL))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader(
                                                "Content-Type", MediaType.APPLICATION_JSON_VALUE)
                                        .withBody(
                                                "{\"outcome\":\"APPROVED\","
                                                        + "\"auth_code\":\"X\","
                                                        + "\"acquirer_reference\":\"acq_y\"}")));

        client.authorize("4242424242424242", 12, 2027, null, 19900L, "DKK", "order-ref-123");

        verify(
                postRequestedFor(urlEqualTo(AUTHORIZE_URL))
                        .withRequestBody(containing("4242424242424242"))
                        .withRequestBody(containing("19900"))
                        .withRequestBody(containing("DKK"))
                        .withRequestBody(containing("order-ref-123")));
    }
}
