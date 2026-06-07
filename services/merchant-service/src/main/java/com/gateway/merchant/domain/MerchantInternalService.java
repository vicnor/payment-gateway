package com.gateway.merchant.domain;

import com.gateway.merchant.persistence.ApiKeyRepository;
import com.gateway.merchant.persistence.MerchantRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MerchantInternalService {

    private final ApiKeyRepository apiKeyRepository;
    private final MerchantRepository merchantRepository;

    public MerchantInternalService(
            ApiKeyRepository apiKeyRepository, MerchantRepository merchantRepository) {
        this.apiKeyRepository = apiKeyRepository;
        this.merchantRepository = merchantRepository;
    }

    @Cacheable("internalApiKeysByPrefix")
    @Transactional(readOnly = true)
    public List<ApiKey> findCandidatesByPrefix(String prefix) {
        return apiKeyRepository.findByKeyPrefixAndRevokedAtIsNull(prefix);
    }

    @Cacheable("internalMerchantsById")
    @Transactional(readOnly = true)
    public Optional<Merchant> findMerchant(String id) {
        return merchantRepository.findById(id);
    }
}
