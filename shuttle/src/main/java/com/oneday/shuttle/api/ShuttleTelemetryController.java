package com.oneday.shuttle.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.shuttle.dto.ShuttleTelemetryRequest;
import com.oneday.shuttle.service.ShuttleTrackingService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** The one door the shuttle-agent app POSTs GPS pings to; overwrites the agent's live-status row in-process. */
@RestController
class ShuttleTelemetryController {

    private final ShuttleTrackingService trackingService;

    ShuttleTelemetryController(ShuttleTrackingService trackingService) {
        this.trackingService = trackingService;
    }

    @PostMapping("/api/v1/shuttle/{agentId}/telemetry")
    public Map<String, Object> telemetry(@AuthenticationPrincipal AuthUserDetails principal,
                                         @PathVariable UUID agentId,
                                         @RequestBody ShuttleTelemetryRequest request) {
        ShuttleAuthz.requireAgent(principal, agentId);
        trackingService.ping(agentId, ShuttleAuthz.city(principal), request.lat(), request.lon());
        return Map.of("status", "ok");
    }
}
