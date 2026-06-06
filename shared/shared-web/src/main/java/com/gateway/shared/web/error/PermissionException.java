package com.gateway.shared.web.error;

import org.springframework.http.HttpStatus;

public final class PermissionException extends ApiException {

    public PermissionException(String message) {
        super("permission_error", "permission_error", message, HttpStatus.FORBIDDEN);
    }

    public PermissionException() {
        this("You do not have permission to perform this action.");
    }
}
