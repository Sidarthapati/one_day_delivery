package com.oneday.routing.service.port;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * No cutoff known until M9 (airline) ships. When it lands, provide a real impl annotated
 * {@code @Primary} to override this (as M3's pattern).
 */
@Component
class NoOpFlightCutoffPort implements FlightCutoffPort {

    @Override
    public Optional<Instant> outboundFlightCutoff(UUID cityId, LocalDate date) {
        return Optional.empty();
    }
}
