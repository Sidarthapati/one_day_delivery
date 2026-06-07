package com.oneday.routing.repository;

import com.oneday.routing.domain.RoutePlanStop;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RoutePlanStopRepository extends JpaRepository<RoutePlanStop, UUID> {

    List<RoutePlanStop> findByRoutePlanIdAndVanIdAndLoopIndexOrderByStopSeq(UUID routePlanId, UUID vanId, int loopIndex);

    List<RoutePlanStop> findByRoutePlanId(UUID routePlanId);
}
