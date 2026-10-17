package com.oneday.auth.repository;

import com.oneday.auth.domain.DaOnboardingCandidate;
import com.oneday.auth.domain.OnboardingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DaOnboardingCandidateRepository extends JpaRepository<DaOnboardingCandidate, UUID> {

    Optional<DaOnboardingCandidate> findByInviteToken(String inviteToken);

    boolean existsByEmail(String email);

    List<DaOnboardingCandidate> findAllByOrderByCreatedAtDesc();

    List<DaOnboardingCandidate> findByStatusOrderByCreatedAtDesc(OnboardingStatus status);

    /** Candidate counts grouped by status — powers the funnel summary. */
    @Query("SELECT c.status, COUNT(c) FROM DaOnboardingCandidate c GROUP BY c.status")
    List<Object[]> countByStatusGrouped();

    /** Next value of the human-friendly employee-id sequence, assigned at provisioning. */
    @Query(value = "SELECT nextval('da_employee_id_seq')", nativeQuery = true)
    long nextEmployeeSeq();
}
