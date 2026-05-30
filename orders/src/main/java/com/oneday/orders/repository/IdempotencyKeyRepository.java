package com.oneday.orders.repository;

import com.oneday.orders.domain.IdempotencyKey;
import com.oneday.orders.domain.IdempotencyKeyId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, IdempotencyKeyId> {

    /**
     * Bulk-deletes all idempotency keys whose TTL has expired.
     * Must be called in a transaction (or is self-transactional via {@code @Transactional}).
     * Typically invoked by a nightly {@code @Scheduled} cleanup job.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM IdempotencyKey k WHERE k.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
