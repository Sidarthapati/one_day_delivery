package com.oneday.dispatch.metrics;

import com.oneday.dispatch.service.DaStatusService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health of M5's in-memory dispatch authority: reports how many DAs are loaded and whether a shift is
 * active. Always UP (the maps are in-process); the details let ops see whether a shift is loaded.
 */
@Component("dispatch")
public class DispatchHealthIndicator implements HealthIndicator {

    private final DaStatusService daStatusService;

    public DispatchHealthIndicator(DaStatusService daStatusService) {
        this.daStatusService = daStatusService;
    }

    @Override
    public Health health() {
        int loaded = daStatusService.loadedDaIds().size();
        return Health.up()
                .withDetail("loadedDas", loaded)
                .withDetail("shiftLoaded", loaded > 0)
                .build();
    }
}
