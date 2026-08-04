package com.gateway.payment.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gateway.shared.web.error.AcquirerUnavailableException;
import com.gateway.shared.web.error.ConflictException;
import com.gateway.shared.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * HTTP implementation of {@link TokenServiceClient} backed by Spring {@link RestClient}.
 *
 * <p>Sends {@code X-Caller-Service: payment-service} and {@code X-Internal-Token} on every request
 * — required by token-service's {@code InternalCallerAuthenticationFilter}.
 */
public class HttpTokenServiceClient implements TokenServiceClient {

    private static final Logger log = LoggerFactory.getLogger(HttpTokenServiceClient.class);

    private final RestClient restClient;

    public HttpTokenServiceClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public DetokenizeData detokenize(String token) {
        try {
            DetokenizeResponse response =
                    restClient
                            .post()
                            .uri("/internal/v1/tokens/{token}/detokenize", token)
                            .retrieve()
                            .body(DetokenizeResponse.class);
            if (response == null) {
                throw new AcquirerUnavailableException("token-service returned empty body");
            }
            return new DetokenizeData(
                    response.pan(),
                    response.expMonth(),
                    response.expYear(),
                    response.brand(),
                    response.last4(),
                    response.country(),
                    response.funding());
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new NotFoundException("Token", token);
            }
            if (ex.getStatusCode() == HttpStatus.CONFLICT) {
                throw new ConflictException("token_already_used", "Token has already been used.");
            }
            log.warn("token-service detokenize error: status={}", ex.getStatusCode(), ex);
            throw new AcquirerUnavailableException("token-service unavailable: " + ex.getMessage());
        } catch (RestClientException ex) {
            log.warn("token-service detokenize connection error", ex);
            throw new AcquirerUnavailableException("token-service unreachable: " + ex.getMessage());
        }
    }

    /** JSON response shape from token-service (snake_case via global Jackson strategy). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DetokenizeResponse(
            String pan,
            int expMonth,
            int expYear,
            String brand,
            String last4,
            String country,
            String funding) {}
}
