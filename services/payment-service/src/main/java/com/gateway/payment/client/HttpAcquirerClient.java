package com.gateway.payment.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gateway.shared.web.error.AcquirerUnavailableException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * HTTP implementation of {@link AcquirerClient} backed by Spring {@link RestClient}.
 *
 * <p>Sends {@code X-Caller-Service: payment-service} and {@code X-Internal-Token} on every request
 * — required by test-acquirer-service's {@code InternalCallerAuthenticationFilter}.
 *
 * <p>HTTP 504 from the acquirer (timeout test card) is mapped to {@link
 * AcquirerUnavailableException} and no payment row is written (ambiguous outcome).
 */
public class HttpAcquirerClient implements AcquirerClient {

    private static final Logger log = LoggerFactory.getLogger(HttpAcquirerClient.class);

    private final RestClient restClient;

    public HttpAcquirerClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public AcquireResult authorize(
            String pan,
            int expMonth,
            int expYear,
            String cvv,
            long amount,
            String currency,
            String reference) {

        // Build request body as a map; cvv may be null (v1 single-use-token flow — see ADR-0001)
        Map<String, Object> body =
                cvv != null
                        ? Map.of(
                                "pan", pan,
                                "exp_month", expMonth,
                                "exp_year", expYear,
                                "cvv", cvv,
                                "amount", amount,
                                "currency", currency,
                                "reference", reference)
                        : Map.of(
                                "pan", pan,
                                "exp_month", expMonth,
                                "exp_year", expYear,
                                "amount", amount,
                                "currency", currency,
                                "reference", reference);

        try {
            AuthorizeResponse response =
                    restClient
                            .post()
                            .uri("/internal/v1/authorize")
                            .body(body)
                            .retrieve()
                            .body(AuthorizeResponse.class);
            if (response == null) {
                throw new AcquirerUnavailableException("acquirer returned empty body");
            }
            return new AcquireResult(
                    response.outcome(),
                    response.authCode(),
                    response.acquirerReference(),
                    response.errorCode());
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode() == HttpStatus.GATEWAY_TIMEOUT) {
                log.warn("acquirer timed out (504) for reference={}", reference);
                throw new AcquirerUnavailableException("acquirer timed out");
            }
            log.warn("acquirer error: status={} reference={}", ex.getStatusCode(), reference, ex);
            throw new AcquirerUnavailableException("acquirer error: " + ex.getMessage());
        } catch (RestClientException ex) {
            log.warn("acquirer connection error for reference={}", reference, ex);
            throw new AcquirerUnavailableException("acquirer unreachable: " + ex.getMessage());
        }
    }

    /** JSON response shape from the acquirer (snake_case via global Jackson strategy). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AuthorizeResponse(
            AcquirerOutcome outcome, String authCode, String acquirerReference, String errorCode) {}
}
