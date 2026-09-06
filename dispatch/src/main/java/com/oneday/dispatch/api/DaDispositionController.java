package com.oneday.dispatch.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.dispatch.dto.request.DispositionRequest;
import com.oneday.dispatch.dto.response.DispositionResponse;
import com.oneday.dispatch.dto.response.DispositionSlotsResponse;
import com.oneday.dispatch.dto.response.ManagerDispositionEntry;
import com.oneday.dispatch.service.DaDispositionService;
import com.oneday.grid.service.GridService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * DA self-service dispositions (breaks / auxiliary company-work / day-off). The DA reads his allowed
 * slots and raises / ends a break; the station manager reads the pending + overstayed queue and
 * approves / rejects auxiliary requests. A day-off, and any overstay the manager decides to act on,
 * uses the existing attendance "mark absent → reassign" flow (this controller does not vacate).
 */
@RestController
public class DaDispositionController {

    private final DaDispositionService service;
    private final GridService gridService;

    public DaDispositionController(DaDispositionService service, GridService gridService) {
        this.service = service;
        this.gridService = gridService;
    }

    // ── DA-facing ──────────────────────────────────────────────────────────────

    @GetMapping("/dispatch/da/{daId}/disposition/slots")
    public DispositionSlotsResponse slots(@PathVariable UUID daId,
                                          @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireDaSelf(principal, daId);
        return service.slots(daId);
    }

    @PostMapping("/dispatch/da/{daId}/disposition/request")
    public DispositionResponse request(@PathVariable UUID daId,
                                       @RequestBody DispositionRequest body,
                                       @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireDaSelf(principal, daId);
        return service.request(daId, body, Authz.requireUserId(principal));
    }

    @PostMapping("/dispatch/da/{daId}/disposition/end")
    public DispositionResponse end(@PathVariable UUID daId,
                                   @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireDaSelf(principal, daId);
        return service.end(daId);
    }

    // ── Manager-facing ─────────────────────────────────────────────────────────

    @GetMapping("/dispatch/disposition/pending")
    public List<ManagerDispositionEntry> pending(@RequestParam(required = false) UUID cityId,
                                                 @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.STATION_MANAGER);
        return service.managerView(resolveCity(principal, cityId));
    }

    @PostMapping("/dispatch/disposition/{id}/approve")
    public DispositionResponse approve(@PathVariable UUID id,
                                       @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.STATION_MANAGER);
        UUID scope = Authz.isAdmin(principal) ? null : managerCity(principal);
        return service.approve(id, Authz.requireUserId(principal), scope);
    }

    @PostMapping("/dispatch/disposition/{id}/reject")
    public DispositionResponse reject(@PathVariable UUID id,
                                      @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.STATION_MANAGER);
        UUID scope = Authz.isAdmin(principal) ? null : managerCity(principal);
        return service.reject(id, Authz.requireUserId(principal), scope);
    }

    // ── city scoping (mirrors AttendanceController) ────────────────────────────

    private UUID resolveCity(AuthUserDetails principal, UUID cityIdParam) {
        if (Authz.isAdmin(principal)) {
            if (cityIdParam == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ADMIN must specify cityId");
            }
            return cityIdParam;
        }
        UUID city = managerCity(principal);
        if (cityIdParam != null && !cityIdParam.equals(city)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot act outside your city");
        }
        return city;
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
