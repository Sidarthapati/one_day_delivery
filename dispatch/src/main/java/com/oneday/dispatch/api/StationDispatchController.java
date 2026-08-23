package com.oneday.dispatch.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.dispatch.dto.request.AssignDeferredRequest;
import com.oneday.dispatch.dto.response.DeferredAssignResponse;
import com.oneday.dispatch.dto.response.DispatchExecutionStats;
import com.oneday.dispatch.dto.response.TileQueueResponse;
import com.oneday.dispatch.service.DispatchMetricsService;
import com.oneday.dispatch.service.StationDispatchService;
import com.oneday.grid.service.GridService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Station-manager dispatch view. STATION_MANAGER sees only their own city's tiles; ADMIN sees any.
 * The manager's city scope comes from their M1 principal ({@code User.cityId}).
 */
@RestController
public class StationDispatchController {

    private final StationDispatchService stationDispatchService;
    private final DispatchMetricsService dispatchMetricsService;
    private final GridService gridService;

    public StationDispatchController(StationDispatchService stationDispatchService,
                                     DispatchMetricsService dispatchMetricsService,
                                     GridService gridService) {
        this.stationDispatchService = stationDispatchService;
        this.dispatchMetricsService = dispatchMetricsService;
        this.gridService = gridService;
    }

    /**
     * Control-tower execution view — the delivery attempt-success gauge and per-DA pace for a date
     * (defaults to today). Same city scope as the tile views: STATION_MANAGER sees their own city,
     * ADMIN all cities.
     */
    @GetMapping("/dispatch/execution")
    public DispatchExecutionStats execution(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.STATION_MANAGER);
        UUID scopeCityId = Authz.isAdmin(principal) ? null : managerCity(principal);
        return dispatchMetricsService.execution(date != null ? date : LocalDate.now(), scopeCityId);
    }

    @GetMapping("/dispatch/tiles/{tileId}/queue")
    public TileQueueResponse tileQueue(@PathVariable UUID tileId,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                       @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.STATION_MANAGER);
        UUID scopeCityId = Authz.isAdmin(principal) ? null : managerCity(principal);
        return stationDispatchService.tileQueue(tileId, date, scopeCityId);
    }

    /**
     * Manually assign a PENDING deferred pickup/delivery to a chosen DA — the station manager's
     * override of the automatic cheapest-least-loaded pick. Still cron-feasibility gated: a rejection
     * comes back as {@code outcome: "DEFERRED"}, not an HTTP error, since the row itself is unchanged.
     */
    @PostMapping("/dispatch/tiles/{tileId}/deferred/{deferredId}/assign")
    public DeferredAssignResponse assignDeferred(@PathVariable UUID tileId, @PathVariable UUID deferredId,
                                                 @Valid @RequestBody AssignDeferredRequest request,
                                                 @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.STATION_MANAGER);
        UUID scopeCityId = Authz.isAdmin(principal) ? null : managerCity(principal);
        return DeferredAssignResponse.from(
                stationDispatchService.assignDeferred(tileId, deferredId, request.daId(), scopeCityId));
    }

    /** Manually escalate a PENDING deferred task to M11, instead of waiting for the retry job's cap. */
    @PostMapping("/dispatch/tiles/{tileId}/deferred/{deferredId}/escalate")
    public ResponseEntity<Void> escalateDeferred(@PathVariable UUID tileId, @PathVariable UUID deferredId,
                                                 @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.STATION_MANAGER);
        UUID scopeCityId = Authz.isAdmin(principal) ? null : managerCity(principal);
        stationDispatchService.escalateDeferred(tileId, deferredId, scopeCityId);
        return ResponseEntity.noContent().build();
    }

    /** The station manager's city as a UUID (accepts a UUID or a city code in {@code User.cityId}). */
    private UUID managerCity(AuthUserDetails principal) {
        String city = principal.getUser().getCityId();
        if (city == null || city.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Station manager has no city scope");
        }
        try {
            return UUID.fromString(city);
        } catch (IllegalArgumentException notUuid) {
            return gridService.resolveCityId(city);
        }
    }
}
