package com.oneday.orders.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for pickup-slot capacity.
 *
 * <pre>{@code
 * orders:
 *   pickup-slot:
 *     max-per-slot: 50
 * }</pre>
 */
@Component
@ConfigurationProperties(prefix = "orders.pickup-slot")
@Validated
public class PickupSlotProperties {

    /**
     * Max active (non-cancelled) pickups reservable in one (origin city, 2-hour slot) before the slot is
     * full and further bookings for it are rejected. An ops capacity knob — tune per real DA throughput.
     * Default: 50.
     */
    @Positive
    private int maxPerSlot = 50;

    public int getMaxPerSlot() { return maxPerSlot; }
    public void setMaxPerSlot(int maxPerSlot) { this.maxPerSlot = maxPerSlot; }
}
