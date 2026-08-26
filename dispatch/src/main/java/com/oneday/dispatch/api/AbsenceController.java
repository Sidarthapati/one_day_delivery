package com.oneday.dispatch.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.dispatch.dto.request.MarkAbsentRequest;
import com.oneday.dispatch.dto.response.AbsenceApplyResponse;
import com.oneday.dispatch.dto.response.AbsencePreviewResponse;
import com.oneday.dispatch.dto.response.DaRosterEntry;
import com.oneday.dispatch.service.AbsenceReassignmentService;
import com.oneday.grid.service.GridService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Station-manager control for midday DA absence. Mark one or more DAs absent to preview the
 * reassignment (hexes split among neighbors, tasks following their hex, custody handoffs), then apply
 * it — or let it auto-apply after the timeout. STATION_MANAGER is scoped to their own city; ADMIN may
 * act on any city (passing {@code cityId} in the body).
 */
@RestController
public class AbsenceController {

    private final AbsenceReassignmentService absenceService;
    private final GridService gridService;

    public AbsenceController(AbsenceReassignmentService absenceService, GridService gridService) {
        this.absenceService = absenceService;
        this.gridService = gridService;
    }

    /**
     * The DAs on shift for the absence picker — every DA on the clock (any task type), so a DA doing
     * only pickups can be marked absent (the delivery scorecards would hide them). ADMIN may pass
     * {@code cityId} to scope to one city (omit for all); a STATION_MANAGER is pinned to their city.
     */
    @GetMapping("/dispatch/absence/roster")
    public List<DaRosterEntry> roster(@RequestParam(required = false) UUID cityId,
                                      @RequestParam(required = false)
                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                      @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.STATION_MANAGER);
        UUID scope = Authz.isAdmin(principal) ? cityId : managerCity(principal);
        LocalDate day = date != null ? date : LocalDate.now(ZoneId.of("Asia/Kolkata"));
        return absenceService.roster(scope, day);
    }

    /** Preview (and stage as PENDING) the reassignment for the marked DAs. */
    @PostMapping("/dispatch/absence/preview")
    public AbsencePreviewResponse preview(@Valid @RequestBody MarkAbsentRequest request,
                                          @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.STATION_MANAGER);
        UUID cityId = resolveCity(principal, request.cityId());
        UUID actor = Authz.requireUserId(principal);
        return absenceService.preview(cityId, request.daIds(), request.reason(), actor);
    }

    /** Apply a previewed plan on the manager's approval (before the auto-approve timeout fires). */
    @PostMapping("/dispatch/absence/{eventId}/apply")
    public AbsenceApplyResponse apply(@PathVariable UUID eventId,
                                      @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.STATION_MANAGER);
        UUID actor = Authz.requireUserId(principal);
        // A STATION_MANAGER may only apply an event in their own city; ADMIN (null scope) any city.
        UUID scopeCityId = Authz.isAdmin(principal) ? null : managerCity(principal);
        return absenceService.apply(eventId, actor, scopeCityId);
    }

    /**
     * The city this action targets: a STATION_MANAGER is pinned to their own city (a mismatched body
     * city is rejected); ADMIN uses the body {@code cityId} (required for them).
     */
    private UUID resolveCity(AuthUserDetails principal, UUID requestedCity) {
        if (Authz.isAdmin(principal)) {
            if (requestedCity == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ADMIN must specify cityId");
            }
            return requestedCity;
        }
        UUID managerCity = managerCity(principal);
        if (requestedCity != null && !requestedCity.equals(managerCity)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot act outside your city");
        }
        return managerCity;
    }

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
