package com.gateway.merchant.api.mapper;

import com.gateway.merchant.api.dto.MerchantResponse;
import com.gateway.merchant.domain.Merchant;

public final class MerchantMapper {

    private MerchantMapper() {}

    public static MerchantResponse toResponse(Merchant merchant) {
        return new MerchantResponse(
                merchant.getId(),
                merchant.getName(),
                merchant.getCallbackUrl(),
                merchant.getReturnUrlPattern(),
                merchant.getCancelUrlPattern(),
                merchant.getBranding(),
                merchant.getMode(),
                merchant.getStatus(),
                merchant.getCreatedAt(),
                merchant.getUpdatedAt());
    }
}
