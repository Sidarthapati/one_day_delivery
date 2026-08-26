package com.oneday.dispatch.repository;

import com.oneday.dispatch.domain.AbsenceStatus;
import com.oneday.dispatch.domain.DaAbsenceEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DaAbsenceEventRepository extends JpaRepository<DaAbsenceEvent, UUID> {

    /** PENDING plans whose auto-approve deadline has passed — the auto-apply sweep. */
    List<DaAbsenceEvent> findByStatusAndAutoApproveAtBefore(AbsenceStatus status, Instant cutoff);

    /** Recent absence events for a city (console history), newest first. */
    List<DaAbsenceEvent> findByCityIdOrderByCreatedAtDesc(UUID cityId);
}
