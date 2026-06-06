package com.gateway.shared.web.error;

import org.springframework.http.HttpStatus;

public final class NotFoundException extends ApiException {

    public NotFoundException(String resource, String id) {
        super(
                "not_found",
                "resource_not_found",
                resource + " not found: " + id,
                HttpStatus.NOT_FOUND);
    }

    public NotFoundException(String message) {
        super("not_found", "resource_not_found", message, HttpStatus.NOT_FOUND);
    }
}
