package com.gateway.merchant.api;

import com.gateway.merchant.api.dto.MerchantResponse;
import com.gateway.merchant.api.dto.internal.ApiKeyCandidatesResponse;
import com.gateway.merchant.api.mapper.MerchantMapper;
import com.gateway.merchant.api.mapper.internal.ApiKeyCandidateMapper;
import com.gateway.merchant.domain.MerchantInternalService;
import com.gateway.shared.web.error.NotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1")
public class MerchantInternalController {

    private final MerchantInternalService merchantInternalService;

    public MerchantInternalController(MerchantInternalService merchantInternalService) {
        this.merchantInternalService = merchantInternalService;
    }

    @GetMapping("/api-keys/{prefix}")
    public ApiKeyCandidatesResponse getApiKeyCandidates(@PathVariable String prefix) {
        return ApiKeyCandidateMapper.toResponse(
                merchantInternalService.findCandidatesByPrefix(prefix));
    }

    @GetMapping("/merchants/{id}")
    public MerchantResponse getMerchant(@PathVariable String id) {
        return merchantInternalService
                .findMerchant(id)
                .map(MerchantMapper::toResponse)
                .orElseThrow(() -> new NotFoundException("merchant", id));
    }
}
