package com.gateway.merchant.persistence;

import com.gateway.merchant.domain.WebhookSecret;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookSecretRepository extends JpaRepository<WebhookSecret, UUID> {

    List<WebhookSecret> findByMerchantIdAndActive(String merchantId, boolean active);
}
