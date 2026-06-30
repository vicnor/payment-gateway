package com.gateway.acquirer.domain;

import com.gateway.acquirer.api.dto.AuthorizeResponse;

public record AuthorizeResult(AuthorizeResponse response, int httpStatus) {

    public static AuthorizeResult ok(AuthorizeResponse response) {
        return new AuthorizeResult(response, 200);
    }

    public static AuthorizeResult gatewayTimeout(AuthorizeResponse response) {
        return new AuthorizeResult(response, 504);
    }
}
