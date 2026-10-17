package com.oneday.auth.repository;

import com.oneday.auth.domain.DaBgvCheck;
import com.oneday.common.port.dto.bgv.BgvCheckStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DaBgvCheckRepository extends JpaRepository<DaBgvCheck, UUID> {

    List<DaBgvCheck> findByCandidateIdOrderByCheckType(UUID candidateId);

    List<DaBgvCheck> findByCandidateIdAndStatus(UUID candidateId, BgvCheckStatus status);

    /** Distinct candidates that still have at least one in-flight check — the poll job's work list. */
    @org.springframework.data.jpa.repository.Query(
            "SELECT DISTINCT c.candidateId FROM DaBgvCheck c WHERE c.status = :status")
    List<UUID> findCandidateIdsWithStatus(
            @org.springframework.data.repository.query.Param("status") BgvCheckStatus status);
}
