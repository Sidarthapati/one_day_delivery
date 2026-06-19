package com.oneday.routing.api;

import com.oneday.routing.dto.RecoveryRequest;
import com.oneday.routing.service.RecoveryService;
import com.oneday.routing.service.model.RecoverySummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Intraday recovery endpoint (§13.5, §17.2). A station manager reports a broken van and dispatches a
 * recovery van; M6 moves the broken van's open manifest items to the recovery van and emits
 * VAN_BREAKDOWN. This is the only sanctioned intraday fleet change (C18, NFR-3) — real M1
 * station-manager scoping is wired the same way RoutePlanController takes its actor.
 */
@RestController
@RequestMapping("/routing")
public class RecoveryController {

    private static final Logger log = LoggerFactory.getLogger(RecoveryController.class);

    private final RecoveryService recoveryService;
    private final Clock clock;

    RecoveryController(RecoveryService recoveryService, Clock clock) {
        this.recoveryService = recoveryService;
        this.clock = clock;
    }

    @PostMapping("/vans/{vanId}/recovery")
    public RecoverySummary recover(@PathVariable UUID vanId, @RequestBody RecoveryRequest request) {
        LocalDate date = request.dateOrToday(LocalDate.now(clock));
        log.info("Recovery requested for van {} → recovery van {} ({})", vanId, request.recoveryVanId(), date);
        return recoveryService.recoverVan(vanId, request.recoveryVanId(), request.cityId(), date,
                request.lastLat(), request.lastLon());
    }
}
