package com.gateway.shared.web.error;

import org.springframework.http.HttpStatus;

public abstract sealed class ApiException extends RuntimeException
        permits NotFoundException,
                ValidationException,
                ConflictException,
                IdempotencyConflictException,
                AuthenticationException,
                PermissionException,
                RateLimitException,
                AcquirerUnavailableException {

    private final String type;
    private final String code;
    private final String param;
    private final HttpStatus status;

    protected ApiException(
            String type, String code, String message, String param, HttpStatus status) {
        super(message);
        this.type = type;
        this.code = code;
        this.param = param;
        this.status = status;
    }

    protected ApiException(String type, String code, String message, HttpStatus status) {
        this(type, code, message, null, status);
    }

    public String type() {
        return type;
    }

    public String code() {
        return code;
    }

    public String param() {
        return param;
    }

    public HttpStatus status() {
        return status;
    }
}
