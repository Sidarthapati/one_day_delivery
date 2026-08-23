package com.oneday.dispatch.repository;

import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.domain.TaskType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DispatchQueueRepository extends JpaRepository<DispatchQueue, UUID> {

    /** A DA's queue for the day in execution order. */
    List<DispatchQueue> findByDaIdAndOperatingDateOrderByQueuePosition(UUID daId, LocalDate operatingDate);

    /** A DA's tasks for the day filtered by status (e.g. QUEUED + IN_PROGRESS). */
    List<DispatchQueue> findByDaIdAndOperatingDateAndStatusIn(
            UUID daId, LocalDate operatingDate, Collection<TaskStatus> statuses);

    /** All tasks routed to a tile on a date — station view DA discovery + city resolution. */
    List<DispatchQueue> findByTileIdAndOperatingDate(UUID tileId, LocalDate operatingDate);

    /** All tasks for a city on a date (demo state + reset). */
    List<DispatchQueue> findByCityIdAndOperatingDate(UUID cityId, LocalDate operatingDate);

    /** Active-task counts per (city, tile, status) for a date — feeds the tile-queue-depth publisher. */
    @Query("""
            select new com.oneday.dispatch.repository.TileDepthCount(d.cityId, d.tileId, d.status, count(d))
            from DispatchQueue d
            where d.operatingDate = :date
              and d.status in (com.oneday.dispatch.domain.TaskStatus.QUEUED,
                               com.oneday.dispatch.domain.TaskStatus.IN_PROGRESS)
            group by d.cityId, d.tileId, d.status
            """)
    List<TileDepthCount> activeDepthByTile(@Param("date") LocalDate date);

    /**
     * The single ACTIVE task for a shipment+type, mirroring the partial unique index
     * (FAILED/CANCELLED excluded). Used as the idempotency guard before assignment.
     */
    @Query("""
            select d from DispatchQueue d
            where d.shipmentId = :shipmentId
              and d.taskType = :taskType
              and d.status not in (com.oneday.dispatch.domain.TaskStatus.FAILED,
                                   com.oneday.dispatch.domain.TaskStatus.CANCELLED)
            """)
    Optional<DispatchQueue> findActiveByShipmentIdAndTaskType(
            @Param("shipmentId") UUID shipmentId, @Param("taskType") TaskType taskType);

    /**
     * Active tasks for a shipment across both legs (pickup + delivery), newest assignment first.
     * Used by live tracking to find the DA currently carrying the parcel; QUEUED/IN_PROGRESS only.
     */
    @Query("""
            select d from DispatchQueue d
            where d.shipmentId = :shipmentId
              and d.status in (com.oneday.dispatch.domain.TaskStatus.QUEUED,
                               com.oneday.dispatch.domain.TaskStatus.IN_PROGRESS)
            order by d.assignedAt desc
            """)
    List<DispatchQueue> findActiveByShipmentId(@Param("shipmentId") UUID shipmentId);

    /**
     * Delivery attempt outcome for a city/date — COMPLETED vs FAILED DELIVERY tasks (attempt-success
     * gauge). Native (Postgres FILTER); status/task_type are EnumType.STRING so they compare as text.
     * {@code city} null → all cities.
     */
    @Query(value = """
            SELECT COUNT(*) FILTER (WHERE status = 'COMPLETED') AS completed,
                   COUNT(*) FILTER (WHERE status = 'FAILED')    AS failed
            FROM dispatch_queue
            WHERE task_type = 'DELIVERY' AND operating_date = :date
              AND (:city IS NULL OR city_id = :city)
            """, nativeQuery = true)
    DeliveryOutcome deliveryOutcome(@Param("city") UUID city, @Param("date") LocalDate date);

    /**
     * Per-DA pace for a city/date: stops done, stops in the last hour (current pace), open tasks, and the
     * DA's first assignment time (for average-per-hour). Native (Postgres FILTER + interval).
     * {@code city} null → all cities.
     */
    @Query(value = """
            SELECT da_id AS daId,
                   COUNT(*) FILTER (WHERE status = 'COMPLETED') AS done,
                   COUNT(*) FILTER (WHERE status = 'COMPLETED'
                                      AND completed_at >= now() - interval '1 hour') AS lastHour,
                   COUNT(*) FILTER (WHERE status IN ('QUEUED', 'IN_PROGRESS')) AS pending,
                   MIN(assigned_at) AS firstAssigned
            FROM dispatch_queue
            WHERE operating_date = :date AND (:city IS NULL OR city_id = :city)
            GROUP BY da_id
            """, nativeQuery = true)
    List<DaPaceRow> paceByDa(@Param("city") UUID city, @Param("date") LocalDate date);

    /**
     * Per-day stops (done/failed) for one DA from {@code fromDate} onward — the DA-detail mini history.
     * {@code city} null → all cities. Native (Postgres FILTER); camelCase aliases bind to {@link DaDayStopsRow}.
     */
    @Query(value = """
            SELECT operating_date AS day,
                   COUNT(*) FILTER (WHERE status = 'COMPLETED') AS done,
                   COUNT(*) FILTER (WHERE status = 'FAILED')    AS failed
            FROM dispatch_queue
            WHERE da_id = :daId AND operating_date >= :fromDate
              AND (:city IS NULL OR city_id = :city)
            GROUP BY operating_date
            ORDER BY operating_date
            """, nativeQuery = true)
    List<DaDayStopsRow> paceByDaOverDays(@Param("daId") UUID daId,
                                         @Param("fromDate") LocalDate fromDate,
                                         @Param("city") UUID city);
}
