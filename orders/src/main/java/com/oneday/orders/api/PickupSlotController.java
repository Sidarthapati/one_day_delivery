package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.dto.SlotAvailabilityResponse;
import com.oneday.orders.service.PickupSlotAvailabilityService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Remaining DA-pickup slot capacity for a city, so the Ship form can grey out full slots before the
 * merchant books. Read-only and city-level (not account-scoped) — any B2B user may query it.
 */
@RestController
@RequestMapping("/api/v1/b2b/pickup-slots")
class PickupSlotController {

    private final PickupSlotAvailabilityService availability;

    PickupSlotController(PickupSlotAvailabilityService availability) {
        this.availability = availability;
    }

    /** @param city origin city code (e.g. "DEL"); @param days horizon from today (default 2). */
    @GetMapping
    public SlotAvailabilityResponse slots(
            @AuthenticationPrincipal AuthUserDetails principal,
            @RequestParam("city") String city,
            @RequestParam(value = "days", defaultValue = "2") int days) {
        Authz.requireRole(principal, "B2B_USER");
        return availability.forCity(city, days);
    }
}
