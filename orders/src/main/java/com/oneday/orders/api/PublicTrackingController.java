package com.oneday.orders.api;

import com.oneday.orders.dto.PublicTrackResponse;
import com.oneday.orders.service.TrackingService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Public, unauthenticated white-label tracking. A B2B merchant shares
 * {@code /t/{token}} with their recipient; the page reads this endpoint. Permitted in SecurityConfig
 * ({@code GET /api/v1/track/**}). The token is unguessable and scopes access to exactly one shipment.
 */
@RestController
@RequestMapping("/api/v1/track")
class PublicTrackingController {

    private final TrackingService trackingService;

    PublicTrackingController(TrackingService trackingService) {
        this.trackingService = trackingService;
    }

    @GetMapping("/{token}")
    public PublicTrackResponse track(@PathVariable("token") String token) {
        return trackingService.trackByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tracking link not found"));
    }
}
