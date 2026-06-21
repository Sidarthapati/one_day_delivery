package com.oneday.dispatch.repository;

import com.oneday.dispatch.domain.DeferredDispatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface DeferredDispatchRepository extends JpaRepository<DeferredDispatch, UUID> {

    /** Count of deferrals in a given state for a tile on a date (station view's deferred_count). */
    int countByTileIdAndOperatingDateAndStatus(UUID tileId, LocalDate operatingDate, String status);

    /**
     * PENDING deferrals for a city that are due for retry (retry_after null or already past).
     * Hits the partial index idx_deferred_retry (WHERE status = 'PENDING').
     */
    @Query("""
            select d from DeferredDispatch d
            where d.cityId = :cityId
              and d.status = 'PENDING'
              and (d.retryAfter is null or d.retryAfter <= :now)
            order by d.deferredAt
            """)
    List<DeferredDispatch> findPendingForRetry(@Param("cityId") UUID cityId, @Param("now") Instant now);
}
