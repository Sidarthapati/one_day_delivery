package com.oneday.orders.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A hub RTO work item (feature iii): a return the hub must physically turn around. Field names
 * serialise to snake_case via the global Jackson config.
 */
public record RtoWorklistItem(
        UUID id,
        String originalRef,
        String childRef,
        String returnHubCity,
        String lane,
        boolean needsBagPull,
        Instant createdAt) {}
