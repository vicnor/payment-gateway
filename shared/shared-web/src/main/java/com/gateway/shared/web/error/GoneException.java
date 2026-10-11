package com.gateway.shared.web.error;

import org.springframework.http.HttpStatus;

/** A resource existed but is no longer usable. */
public final class GoneException extends ApiException {
    public GoneException(String code, String message) {
        super("conflict_error", code, message, HttpStatus.GONE);
    }
}
