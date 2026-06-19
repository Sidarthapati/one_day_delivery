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
