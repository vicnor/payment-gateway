package com.gateway.shared.web.error;

import org.springframework.http.HttpStatus;

public final class ServiceUnavailableException extends ApiException {
    public ServiceUnavailableException(String code, String message) {
        super("api_error", code, message, HttpStatus.SERVICE_UNAVAILABLE);
    }
}
