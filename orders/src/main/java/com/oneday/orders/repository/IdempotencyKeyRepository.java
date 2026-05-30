package com.oneday.orders.repository;

import com.oneday.orders.domain.IdempotencyKey;
import com.oneday.orders.domain.IdempotencyKeyId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, IdempotencyKeyId> {

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM IdempotencyKey k WHERE k.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
