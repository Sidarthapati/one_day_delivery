package com.oneday.dispatch.repository;

import com.oneday.dispatch.domain.DaCronAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DaCronAssignmentRepository extends JpaRepository<DaCronAssignment, UUID> {

    /** A DA's cron meeting for the day (unique by da_id + operating_date). */
    Optional<DaCronAssignment> findByDaIdAndOperatingDate(UUID daId, LocalDate operatingDate);

    /** All cron meetings for a city on a date — used at shift load. */
    List<DaCronAssignment> findByOperatingDateAndCityId(LocalDate operatingDate, UUID cityId);
}
