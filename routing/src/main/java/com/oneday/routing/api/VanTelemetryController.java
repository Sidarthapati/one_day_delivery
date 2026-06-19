package com.oneday.routing.api;

import com.oneday.routing.dto.TelemetryAck;
import com.oneday.routing.dto.VanTelemetryRequest;
import com.oneday.routing.service.VanTrackingService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The single HTTPS door the van-driver app POSTs to (§14.1, M6-D-012). One endpoint carries GPS pings
 * and in-loop DELIVER/COLLECT scans; {@code type} discriminates. The controller calls
 * {@link VanTrackingService} directly in-process — Kafka is spent only on the meaningful events that
 * service decides to emit, never on the raw ~10s ping stream.
 */
@RestController
public class VanTelemetryController {

    private final VanTrackingService trackingService;

    VanTelemetryController(VanTrackingService trackingService) {
        this.trackingService = trackingService;
    }

    @PostMapping("/api/v1/van/{vanId}/telemetry")
    public TelemetryAck telemetry(@PathVariable UUID vanId, @RequestBody VanTelemetryRequest request) {
        return trackingService.handle(vanId, request);
    }
}
