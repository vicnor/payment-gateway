package com.gateway.merchant.api.mapper.internal;

import com.gateway.merchant.api.dto.internal.ApiKeyCandidate;
import com.gateway.merchant.api.dto.internal.ApiKeyCandidatesResponse;
import com.gateway.merchant.domain.ApiKey;
import java.util.List;

public final class ApiKeyCandidateMapper {

    private ApiKeyCandidateMapper() {}

    public static ApiKeyCandidate toCandidate(ApiKey apiKey) {
        return new ApiKeyCandidate(
                apiKey.getId(),
                apiKey.getMerchantId(),
                apiKey.getKeyPrefix(),
                apiKey.getKeyHash(),
                apiKey.getMode());
    }

    public static ApiKeyCandidatesResponse toResponse(List<ApiKey> apiKeys) {
        return new ApiKeyCandidatesResponse(
                apiKeys.stream().map(ApiKeyCandidateMapper::toCandidate).toList());
    }
}
