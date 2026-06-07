package com.oneday.routing.repository;

import com.oneday.routing.domain.DaCronSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface DaCronScheduleRepository extends JpaRepository<DaCronSchedule, UUID> {

    List<DaCronSchedule> findByDaIdAndValidDate(UUID daId, LocalDate validDate);

    List<DaCronSchedule> findByRoutePlanId(UUID routePlanId);
}
