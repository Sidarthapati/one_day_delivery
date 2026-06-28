package com.oneday.hub.service.port;

import com.oneday.routing.domain.DaCronSchedule;
import com.oneday.routing.service.RoutePlanLifecycleService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Real M6 wiring for {@link DeliveryRoutePort}: reads the DA's cron schedule from M6's live nightly
 * plan ({@code RoutePlanLifecycleService.cronForDa}). A schedule with a bound van means a loop runs
 * the territory → ROUTE bag; no schedule, or a schedule with no van, means no van runs → empty,
 * which the sort ladder reads as "fall back to a DA-territory bag" (M7-D-012, §8.1). M7 only reads
 * the plan; it never schedules the van (§8.3).
 */
@Component
class RoutingDeliveryRouteAdapter implements DeliveryRoutePort {

    private final RoutePlanLifecycleService routePlanLifecycleService;

    RoutingDeliveryRouteAdapter(RoutePlanLifecycleService routePlanLifecycleService) {
        this.routePlanLifecycleService = routePlanLifecycleService;
    }

    @Override
    public Optional<DeliveryRoute> routeForTerritory(UUID cityId, UUID territoryId, LocalDate date) {
        if (territoryId == null) {
            return Optional.empty();
        }
        List<DaCronSchedule> schedules = routePlanLifecycleService.cronForDa(territoryId, date);
        return schedules.stream()
                .filter(s -> s.getVanId() != null)   // no van bound = no loop runs this territory
                .findFirst()
                .map(s -> new DeliveryRoute(s.getRoutePlanId(), s.getVanId()));
    }
}
