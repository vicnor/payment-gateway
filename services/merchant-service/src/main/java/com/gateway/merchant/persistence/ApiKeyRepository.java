package com.gateway.merchant.persistence;

import com.gateway.merchant.domain.ApiKey;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {}
