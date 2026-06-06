package com.gateway.merchant.domain;

import com.gateway.merchant.persistence.MerchantRepository;
import com.gateway.shared.web.error.NotFoundException;
import com.github.f4b6a3.ulid.UlidCreator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MerchantService {

    private final MerchantRepository merchantRepository;

    public MerchantService(MerchantRepository merchantRepository) {
        this.merchantRepository = merchantRepository;
    }

    @Transactional
    public Merchant create(
            String name,
            String callbackUrl,
            String returnUrlPattern,
            String cancelUrlPattern,
            Branding branding,
            MerchantMode mode) {
        String id = "mer_" + UlidCreator.getUlid();
        Merchant merchant =
                new Merchant(
                        id, name, callbackUrl, returnUrlPattern, cancelUrlPattern, branding, mode);
        return merchantRepository.save(merchant);
    }

    @Transactional(readOnly = true)
    public Merchant findById(String id) {
        return merchantRepository
                .findById(id)
                .orElseThrow(() -> new NotFoundException("Merchant", id));
    }
}
