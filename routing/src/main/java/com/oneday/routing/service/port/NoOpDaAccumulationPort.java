package com.oneday.routing.service.port;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * No accumulation feed until M5/M4 provide it. When ready, provide a real impl annotated
 * {@code @Primary} to override this (as M3's pattern).
 */
@Component
class NoOpDaAccumulationPort implements DaAccumulationPort {

    @Override
    public List<AccumulatedParcel> collectedAwaitingPickup(UUID daId, LocalDate date) {
        return List.of();
    }
}
