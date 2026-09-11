package com.gateway.payment.persistence;

import com.gateway.payment.domain.OutboxRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxRecord, Long> {

    /**
     * Claims up to {@code limit} unpublished rows for this transaction, oldest first, skipping rows
     * already locked by a concurrent publisher (this instance or another pod).
     */
    @Query(
            value =
                    """
                    SELECT * FROM outbox
                    WHERE published_at IS NULL
                    ORDER BY created_at
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                    """,
            nativeQuery = true)
    List<OutboxRecord> claimUnpublished(@Param("limit") int limit);

    @Query("SELECT MIN(o.createdAt) FROM OutboxRecord o WHERE o.publishedAt IS NULL")
    Optional<Instant> oldestUnpublishedCreatedAt();
}
