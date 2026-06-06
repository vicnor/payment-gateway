package com.gateway.merchant.domain;

import com.gateway.merchant.persistence.MerchantRepository;
import com.gateway.merchant.persistence.WebhookSecretRepository;
import com.gateway.shared.web.error.NotFoundException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookSecretService {

    private final MerchantRepository merchantRepository;
    private final WebhookSecretRepository webhookSecretRepository;
    private final Argon2Hasher argon2Hasher;

    public WebhookSecretService(
            MerchantRepository merchantRepository,
            WebhookSecretRepository webhookSecretRepository,
            Argon2Hasher argon2Hasher) {
        this.merchantRepository = merchantRepository;
        this.webhookSecretRepository = webhookSecretRepository;
        this.argon2Hasher = argon2Hasher;
    }

    @Transactional
    public RotatedSecret rotate(String merchantId) {
        merchantRepository
                .findById(merchantId)
                .orElseThrow(() -> new NotFoundException("Merchant", merchantId));

        List<WebhookSecret> current =
                webhookSecretRepository.findByMerchantIdAndActive(merchantId, true);
        current.forEach(WebhookSecret::deactivate);

        String plain = SecretGenerator.generate();
        String hash = argon2Hasher.hash(plain);

        WebhookSecret newSecret = new WebhookSecret(merchantId, hash);
        webhookSecretRepository.save(newSecret);

        return new RotatedSecret(plain, newSecret);
    }
}
