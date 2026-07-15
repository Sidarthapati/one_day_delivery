package com.oneday.orders.service.port;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * No live position known until M9 (airline) provides one. When it lands, a real impl annotated
 * {@code @Primary} overrides this (as M3's pattern).
 */
@Component
class NoOpFlightTrackingPort implements FlightTrackingPort {

    @Override
    public Optional<LivePosition> currentPosition(UUID shipmentId) {
        return Optional.empty();
    }
}
