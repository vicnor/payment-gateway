package com.gateway.token.api.mapper;

import com.gateway.token.api.dto.DetokenizeResponse;
import com.gateway.token.domain.DetokenizeResult;

public final class DetokenizeMapper {

    private DetokenizeMapper() {}

    public static DetokenizeResponse toResponse(DetokenizeResult result) {
        return new DetokenizeResponse(result.pan(), result.expMonth(), result.expYear());
    }
}
