package com.oneday.dispatch.repository;

import com.oneday.dispatch.domain.DeferredDispatch;
import com.oneday.dispatch.domain.TaskType;
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

    /** Deferrals in a given state for a tile on a date (the station view's "unassigned pickups" list). */
    List<DeferredDispatch> findByTileIdAndOperatingDateAndStatus(UUID tileId, LocalDate operatingDate, String status);

    /** All deferrals for a city on a date (demo state + reset). */
    List<DeferredDispatch> findByCityIdAndOperatingDate(UUID cityId, LocalDate operatingDate);

    /** A shipment's deferrals of a given type in a given state — the redelivery idempotency guard. */
    List<DeferredDispatch> findByShipmentIdAndTaskTypeAndStatus(UUID shipmentId, TaskType taskType, String status);

    /** Deferrals in a given state for a city (used to reset the retry budget when a new shift loads). */
    List<DeferredDispatch> findByCityIdAndStatus(UUID cityId, String status);

    /**
     * PENDING deferrals for a city that are due for retry (retry_after null or already past).
     * Hits the partial index idx_deferred_retry (WHERE status = 'PENDING').
     *
     * <p>Two redelivery guards: {@code operating_date <= today} keeps a tomorrow-dated reschedule from
     * firing today, and {@code target_shift null or == currentShift} honours the receiver's chosen
     * Shift 1 / Shift 2 (a null target_shift — every ordinary pickup/delivery deferral — is unaffected).</p>
     */
    @Query("""
            select d from DeferredDispatch d
            where d.cityId = :cityId
              and d.status = 'PENDING'
              and (d.retryAfter is null or d.retryAfter <= :now)
              and d.operatingDate <= :today
              and (d.targetShift is null or d.targetShift = :currentShift)
            order by d.deferredAt
            """)
    List<DeferredDispatch> findPendingForRetry(@Param("cityId") UUID cityId, @Param("now") Instant now,
                                               @Param("today") LocalDate today,
                                               @Param("currentShift") String currentShift);
}
