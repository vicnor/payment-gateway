package com.gateway.token.api.mapper;

import com.gateway.token.api.dto.TokenizeResponse;
import com.gateway.token.domain.TokenResult;

/** Maps the domain result to the API response record. */
public final class TokenizeMapper {

    private TokenizeMapper() {}

    public static TokenizeResponse toResponse(TokenResult result) {
        return new TokenizeResponse(
                result.token(),
                result.brand(),
                result.last4(),
                result.expMonth(),
                result.expYear());
    }
}
