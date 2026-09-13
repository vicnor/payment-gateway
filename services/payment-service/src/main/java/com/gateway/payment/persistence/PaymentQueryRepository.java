package com.gateway.payment.persistence;

import com.gateway.payment.domain.Payment;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;

/** Dynamic keyset query for merchant payment lists. */
@Repository
public class PaymentQueryRepository {

    private final EntityManager entityManager;

    public PaymentQueryRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public List<Payment> findMerchantPage(
            String merchantId,
            Instant createdGte,
            Instant createdLte,
            Instant cursorCreatedAt,
            String cursorExternalId,
            int maxResults) {
        StringBuilder jpql =
                new StringBuilder("SELECT p FROM Payment p WHERE p.merchantId = :merchantId");
        if (createdGte != null) {
            jpql.append(" AND p.createdAt >= :createdGte");
        }
        if (createdLte != null) {
            jpql.append(" AND p.createdAt <= :createdLte");
        }
        if (cursorCreatedAt != null) {
            jpql.append(
                    " AND (p.createdAt < :cursorCreatedAt"
                            + " OR (p.createdAt = :cursorCreatedAt"
                            + " AND p.externalId < :cursorExternalId))");
        }
        jpql.append(" ORDER BY p.createdAt DESC, p.externalId DESC");

        TypedQuery<Payment> query = entityManager.createQuery(jpql.toString(), Payment.class);
        query.setParameter("merchantId", merchantId);
        if (createdGte != null) {
            query.setParameter("createdGte", createdGte);
        }
        if (createdLte != null) {
            query.setParameter("createdLte", createdLte);
        }
        if (cursorCreatedAt != null) {
            query.setParameter("cursorCreatedAt", cursorCreatedAt);
            query.setParameter("cursorExternalId", cursorExternalId);
        }
        return query.setMaxResults(maxResults).getResultList();
    }
}
