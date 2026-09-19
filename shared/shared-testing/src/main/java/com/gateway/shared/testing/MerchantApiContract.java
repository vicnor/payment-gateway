package com.gateway.shared.testing;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import java.net.URI;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriComponentsBuilder;

/** Assertions that bind Merchant API integration tests to the packaged OpenAPI contract. */
public final class MerchantApiContract {

    private static final String SPECIFICATION = "/openapi/merchant-api-v1.yaml";
    private static final OpenApiInteractionValidator VALIDATOR =
            OpenApiInteractionValidator.createForSpecificationUrl(SPECIFICATION).build();

    private MerchantApiContract() {}

    /** Validates successful interactions in full and error responses against their operation. */
    public static void assertConforms(
            HttpMethod method,
            String pathAndQuery,
            HttpHeaders requestHeaders,
            String requestBody,
            ResponseEntity<String> response) {
        URI uri = URI.create(pathAndQuery);
        SimpleResponse apiResponse = response(response);
        ValidationReport report;
        if (response.getStatusCode().is2xxSuccessful()) {
            SimpleRequest.Builder request = new SimpleRequest.Builder(method.name(), uri.getPath());
            requestHeaders.forEach(request::withHeader);
            UriComponentsBuilder.fromUriString(pathAndQuery)
                    .build()
                    .getQueryParams()
                    .forEach(request::withQueryParam);
            if (requestBody != null && !requestBody.isEmpty()) request.withBody(requestBody);
            report = VALIDATOR.validate(request.build(), apiResponse);
        } else {
            report =
                    VALIDATOR.validateResponse(
                            uri.getPath(), Request.Method.valueOf(method.name()), apiResponse);
        }
        assertNoErrors(method, pathAndQuery, response.getStatusCode().value(), report);
    }

    /** Validates a response when the request is intentionally invalid, such as missing auth. */
    public static void assertResponseConforms(
            HttpMethod method, String pathAndQuery, ResponseEntity<String> response) {
        URI uri = URI.create(pathAndQuery);
        ValidationReport report =
                VALIDATOR.validateResponse(
                        uri.getPath(), Request.Method.valueOf(method.name()), response(response));
        assertNoErrors(method, pathAndQuery, response.getStatusCode().value(), report);
    }

    private static SimpleResponse response(ResponseEntity<String> source) {
        SimpleResponse.Builder response =
                SimpleResponse.Builder.status(source.getStatusCode().value());
        source.getHeaders().forEach(response::withHeader);
        if (source.getBody() != null) response.withBody(source.getBody());
        return response.build();
    }

    private static void assertNoErrors(
            HttpMethod method, String path, int status, ValidationReport report) {
        if (!report.hasErrors()) return;
        String messages =
                report.getMessages().stream()
                        .map(Object::toString)
                        .collect(Collectors.joining(System.lineSeparator()));
        throw new AssertionError(
                "Merchant API contract violation for "
                        + method
                        + " "
                        + path
                        + " -> "
                        + status
                        + System.lineSeparator()
                        + messages);
    }
}
