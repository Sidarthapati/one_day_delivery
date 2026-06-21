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
}
