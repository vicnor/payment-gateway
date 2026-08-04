package com.gateway.payment.persistence;

import com.gateway.payment.domain.OutboxRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRepository extends JpaRepository<OutboxRecord, Long> {}
