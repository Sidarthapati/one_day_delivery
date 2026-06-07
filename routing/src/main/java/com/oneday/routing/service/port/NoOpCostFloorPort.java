package com.oneday.routing.service.port;

import com.oneday.routing.config.RoutingProperties;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Flat per-km stub until M2 (pricing) ships its cost floor (M6-D-010).
 * When M2 lands, provide a real impl annotated {@code @Primary} to override this (as M3's pattern).
 */
@Component
class NoOpCostFloorPort implements CostFloorPort {

    private final RoutingProperties properties;

    NoOpCostFloorPort(RoutingProperties properties) {
        this.properties = properties;
    }

    @Override
    public double perKmVanCostInr(UUID cityId) {
        return properties.getCostPerKm();
    }
}
