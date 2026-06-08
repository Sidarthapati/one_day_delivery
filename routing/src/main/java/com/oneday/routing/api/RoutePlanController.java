package com.oneday.routing.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.routing.domain.DaCronSchedule;
import com.oneday.routing.domain.RoutePlan;
import com.oneday.routing.dto.ApproveRequest;
import com.oneday.routing.dto.DaCronScheduleResponse;
import com.oneday.routing.dto.NextMeetingResponse;
import com.oneday.routing.dto.OverrideRequest;
import com.oneday.routing.dto.RoutePlanResponse;
import com.oneday.routing.dto.RoutePlanStopResponse;
import com.oneday.routing.dto.ShuttleTimetableResponse;
import com.oneday.routing.service.RoutePlanLifecycleService;
import com.oneday.routing.service.ShuttleScheduleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Nightly-governance + read API for van route plans (§17.2). Approve / override / replan mutate;
 * the GETs serve M5 (cron schedules), M9 (shuttle) and the ops console. Plan bodies are append-only,
 * so an override returns a new revision rather than editing in place (C17).
 */
@RestController
@RequestMapping("/routing")
public class RoutePlanController {

    private static final Logger log = LoggerFactory.getLogger(RoutePlanController.class);
    private static final TypeReference<List<String>> TIME_LIST = new TypeReference<>() {};

    private final RoutePlanLifecycleService lifecycleService;
    private final ShuttleScheduleService shuttleScheduleService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    RoutePlanController(RoutePlanLifecycleService lifecycleService,
                        ShuttleScheduleService shuttleScheduleService,
                        ObjectMapper objectMapper,
                        Clock clock) {
        this.lifecycleService = lifecycleService;
        this.shuttleScheduleService = shuttleScheduleService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    @GetMapping("/plans/{cityId}")
    public RoutePlanResponse getPlan(
            @PathVariable UUID cityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate d = date != null ? date : LocalDate.now(clock);
        return lifecycleService.activePlan(cityId, d)
                .map(RoutePlanResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No active route plan for cityId=" + cityId + " date=" + d));
    }

    @GetMapping("/plans/{cityId}/vans/{vanId}/stops")
    public List<RoutePlanStopResponse> getVanStops(
            @PathVariable UUID cityId,
            @PathVariable UUID vanId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate d = date != null ? date : LocalDate.now(clock);
        RoutePlan plan = lifecycleService.activePlan(cityId, d)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No active route plan for cityId=" + cityId + " date=" + d));
        return lifecycleService.stops(plan.getId(), vanId).stream()
                .map(RoutePlanStopResponse::from)
                .toList();
    }

    @GetMapping("/cron/da/{daId}")
    public List<DaCronScheduleResponse> getDaCron(
            @PathVariable UUID daId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate d = date != null ? date : LocalDate.now(clock);
        return lifecycleService.cronForDa(daId, d).stream()
                .map(c -> new DaCronScheduleResponse(c.getDaId(), c.getCityId(), c.getValidDate(),
                        c.getRoutePlanId(), c.getHexVertexId(), c.getVanId(), parseTimes(c.getMeetingTimes())))
                .toList();
    }

    @GetMapping("/cron/da/{daId}/next")
    public NextMeetingResponse getNextMeeting(
            @PathVariable UUID daId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime at) {
        LocalDate today = LocalDate.now(clock);
        LocalTime cutoff = at != null ? at : LocalTime.now(clock);
        return lifecycleService.cronForDa(daId, today).stream()
                .flatMap(c -> parseTimes(c.getMeetingTimes()).stream()
                        .filter(t -> !t.isBefore(cutoff))
                        .map(t -> new NextMeetingResponse(daId, c.getHexVertexId(), c.getVanId(), t)))
                .min((a, b) -> a.nextMeeting().compareTo(b.nextMeeting()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No upcoming meeting for daId=" + daId + " after " + cutoff));
    }

    @GetMapping("/shuttle/{cityId}")
    public ShuttleTimetableResponse getShuttle(
            @PathVariable UUID cityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate d = date != null ? date : LocalDate.now(clock);
        return ShuttleTimetableResponse.from(shuttleScheduleService.timetable(cityId, d));
    }

    // ── Governance ──────────────────────────────────────────────────────────

    @PostMapping("/plans/{planId}/approve")
    public RoutePlanResponse approve(@PathVariable UUID planId, @RequestBody ApproveRequest request) {
        return RoutePlanResponse.from(lifecycleService.approve(planId, request.actorId()));
    }

    @PostMapping("/plans/{planId}/override")
    public RoutePlanResponse override(@PathVariable UUID planId, @RequestBody OverrideRequest request) {
        return RoutePlanResponse.from(lifecycleService.override(planId, request));
    }

    @PostMapping("/plans/{cityId}/replan")
    public RoutePlanResponse replan(
            @PathVariable UUID cityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate d = date != null ? date : LocalDate.now(clock).plusDays(1);
        return RoutePlanResponse.from(lifecycleService.replan(cityId, d));
    }

    private List<LocalTime> parseTimes(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, TIME_LIST).stream().map(LocalTime::parse).toList();
        } catch (Exception e) {
            log.warn("Could not parse meeting_times json '{}': {}", json, e.getMessage());
            return List.of();
        }
    }
}
