package com.gateway.shared.web.error;

import org.springframework.http.HttpStatus;

public final class ConflictException extends ApiException {

    public ConflictException(String code, String message) {
        super("conflict_error", code, message, HttpStatus.CONFLICT);
    }
}
