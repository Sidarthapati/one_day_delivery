package com.oneday.dispatch.repository;

import com.oneday.dispatch.domain.DaGpsPing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DaGpsPingRepository extends JpaRepository<DaGpsPing, UUID> {

    /** A DA's breadcrumb trail within a time window, oldest first (route replay / ops query). */
    List<DaGpsPing> findByDaIdAndRecordedAtBetweenOrderByRecordedAtAsc(
            UUID daId, Instant from, Instant to);

    /** The DA's most recent fix — the live-tracking source of truth (written on every ping, unlike the
     *  da_status snapshot which only updates while the DA is loaded in memory). */
    DaGpsPing findTopByDaIdOrderByRecordedAtDesc(UUID daId);

    /** Bulk-delete trail rows older than {@code cutoff} (retention purge). Returns rows removed. */
    @Modifying
    @Transactional
    @Query("delete from DaGpsPing p where p.recordedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
