package com.oneday.shuttle.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.shuttle.dto.BagActionResult;
import com.oneday.shuttle.dto.OutToAirportRequest;
import com.oneday.shuttle.dto.ShuttleQueueResponse;
import com.oneday.shuttle.service.ShuttleActionService;
import com.oneday.shuttle.service.ShuttleQueueService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * The shuttle-agent app's door: the shared city queue and the three actions (out-to-airport batch,
 * request-seal, collect-from-airport). {@code agentId == userId}; the agent's city comes from the JWT.
 */
@RestController
@RequestMapping("/shuttle")
class ShuttleController {

    private final ShuttleQueueService queueService;
    private final ShuttleActionService actionService;
    private final Clock clock;

    ShuttleController(ShuttleQueueService queueService, ShuttleActionService actionService, Clock clock) {
        this.queueService = queueService;
        this.actionService = actionService;
        this.clock = clock;
    }

    @GetMapping("/{agentId}/queue")
    public ShuttleQueueResponse queue(@AuthenticationPrincipal AuthUserDetails principal,
                                      @PathVariable UUID agentId) {
        ShuttleAuthz.requireAgent(principal, agentId);
        return queueService.queue(ShuttleAuthz.city(principal), LocalDate.now(clock));
    }

    @PostMapping("/{agentId}/out-to-airport")
    public BagActionResult outToAirport(@AuthenticationPrincipal AuthUserDetails principal,
                                        @PathVariable UUID agentId,
                                        @Valid @RequestBody OutToAirportRequest request) {
        ShuttleAuthz.requireAgent(principal, agentId);
        return actionService.outToAirport(agentId, ShuttleAuthz.city(principal), request.bagIds());
    }

    @PostMapping("/{agentId}/bags/{bagId}/request-seal")
    public Map<String, Object> requestSeal(@AuthenticationPrincipal AuthUserDetails principal,
                                           @PathVariable UUID agentId, @PathVariable UUID bagId) {
        ShuttleAuthz.requireAgent(principal, agentId);
        actionService.requestSeal(agentId, ShuttleAuthz.city(principal), bagId);
        return Map.of("status", "ok", "bag_id", bagId);
    }

    @PostMapping("/{agentId}/awbs/{awbId}/collect-from-airport")
    public Map<String, Object> collect(@AuthenticationPrincipal AuthUserDetails principal,
                                       @PathVariable UUID agentId, @PathVariable UUID awbId) {
        ShuttleAuthz.requireAgent(principal, agentId);
        int parcels = actionService.collectFromAirport(agentId, awbId);
        return Map.of("status", "ok", "awb_id", awbId, "parcels", parcels);
    }
}
