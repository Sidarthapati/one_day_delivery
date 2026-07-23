package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.dto.ShipmentTrackResponse;
import com.oneday.orders.service.TrackingService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Live tracking for a customer's own shipment — current position, route, and milestone timeline.
 * Scoped like the rest of the customer read path: booking roles only, and only the caller's own
 * shipments (a ref the caller did not book is a 404).
 */
@RestController
@RequestMapping("/api/v1/shipments")
class TrackingController {

    private final TrackingService trackingService;

    TrackingController(TrackingService trackingService) {
        this.trackingService = trackingService;
    }

    @GetMapping("/mine/{ref}/track")
    public ShipmentTrackResponse track(
            @AuthenticationPrincipal AuthUserDetails principal,
            @PathVariable("ref") String ref) {
        Authz.requireCustomerRole(principal, "C2C_CUSTOMER", "B2C_CUSTOMER", "B2B_USER");
        return trackingService.track(Authz.requireUserId(principal), ref)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such shipment: " + ref));
    }
}
