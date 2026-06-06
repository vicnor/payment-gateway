package com.gateway.shared.web.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

public record ErrorEnvelope(ErrorBody error) {

    public static ErrorEnvelope of(
            String type, String code, String message, String param, String requestId) {
        return new ErrorEnvelope(new ErrorBody(type, code, message, param, requestId));
    }

    @JsonInclude(Include.NON_NULL)
    public record ErrorBody(
            String type,
            String code,
            String message,
            String param,
            @JsonProperty("request_id") String requestId) {}
}
