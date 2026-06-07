package com.oneday.routing.service.port;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Nothing ready to load until M7 (hub) ships. When it lands, provide a real impl annotated
 * {@code @Primary} to override this (as M3's pattern).
 */
@Component
class NoOpHubSortPort implements HubSortPort {

    @Override
    public List<ReadyForDeliveryParcel> readyForDelivery(UUID cityId) {
        return List.of();
    }
}
