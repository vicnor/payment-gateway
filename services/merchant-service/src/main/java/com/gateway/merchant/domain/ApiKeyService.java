package com.gateway.merchant.domain;

import com.gateway.merchant.persistence.ApiKeyRepository;
import com.gateway.merchant.persistence.MerchantRepository;
import com.gateway.shared.web.error.ConflictException;
import com.gateway.shared.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApiKeyService {

    private final MerchantRepository merchantRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final Argon2Hasher argon2Hasher;

    public ApiKeyService(
            MerchantRepository merchantRepository,
            ApiKeyRepository apiKeyRepository,
            Argon2Hasher argon2Hasher) {
        this.merchantRepository = merchantRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.argon2Hasher = argon2Hasher;
    }

    @Transactional
    public IssuedApiKey issue(String merchantId, String label) {
        Merchant merchant =
                merchantRepository
                        .findById(merchantId)
                        .orElseThrow(() -> new NotFoundException("Merchant", merchantId));

        if (merchant.getStatus() != MerchantStatus.ACTIVE) {
            throw new ConflictException(
                    "merchant_not_active", "Merchant " + merchantId + " is not active");
        }

        String plain = KeyGenerator.generate(merchant.getMode());
        String prefix = KeyGenerator.keyPrefix(plain);
        String hash = argon2Hasher.hash(plain);

        ApiKey apiKey = new ApiKey(merchantId, prefix, hash, merchant.getMode(), label);
        apiKeyRepository.save(apiKey);

        return new IssuedApiKey(plain, apiKey);
    }
}
