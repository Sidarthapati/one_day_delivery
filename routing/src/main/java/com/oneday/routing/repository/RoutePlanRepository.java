package com.oneday.routing.repository;

import com.oneday.routing.domain.RoutePlan;
import com.oneday.routing.domain.RoutePlanStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface RoutePlanRepository extends JpaRepository<RoutePlan, UUID> {

    List<RoutePlan> findByCityIdAndValidForDateAndStatus(UUID cityId, LocalDate validForDate, RoutePlanStatus status);

    List<RoutePlan> findByCityIdAndValidForDate(UUID cityId, LocalDate validForDate);
}
