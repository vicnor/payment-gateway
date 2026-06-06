package com.gateway.merchant.api.mapper;

import com.gateway.merchant.api.dto.IssueApiKeyResponse;
import com.gateway.merchant.domain.IssuedApiKey;

public final class ApiKeyMapper {

    private ApiKeyMapper() {}

    public static IssueApiKeyResponse toResponse(IssuedApiKey issued) {
        return new IssueApiKeyResponse(
                issued.persisted().getId(),
                issued.plainKey(),
                issued.persisted().getKeyPrefix(),
                issued.persisted().getMode(),
                issued.persisted().getCreatedAt());
    }
}
