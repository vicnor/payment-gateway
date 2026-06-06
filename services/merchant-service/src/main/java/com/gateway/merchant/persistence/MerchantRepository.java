package com.gateway.merchant.persistence;

import com.gateway.merchant.domain.Merchant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantRepository extends JpaRepository<Merchant, String> {}
