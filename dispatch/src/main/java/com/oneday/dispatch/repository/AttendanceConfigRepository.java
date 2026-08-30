package com.oneday.dispatch.repository;

import com.oneday.dispatch.domain.AttendanceConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AttendanceConfigRepository extends JpaRepository<AttendanceConfig, UUID> {

    /** The singleton config row (earliest by created_at, in case duplicates ever slip in). */
    Optional<AttendanceConfig> findFirstByOrderByCreatedAtAsc();
}
