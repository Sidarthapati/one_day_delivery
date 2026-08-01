package com.oneday.dispatch.repository;

import com.oneday.dispatch.domain.DaGpsPing;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DaGpsPingRepository extends JpaRepository<DaGpsPing, UUID> {

    /** A DA's breadcrumb trail within a time window, oldest first (route replay / ops query). */
    List<DaGpsPing> findByDaIdAndRecordedAtBetweenOrderByRecordedAtAsc(
            UUID daId, Instant from, Instant to);
}
