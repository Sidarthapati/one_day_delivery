package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.dto.RtoWorklistItem;
import com.oneday.orders.service.RtoWorklistService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Hub RTO worklist (feature iii) — returns the return hub must physically turn around. STATION_MANAGER
 * sees only their own city's queue; ADMIN sees all. Same city-scoping as the cancel/RTO ops actions.
 */
@RestController
@RequestMapping("/api/v1/admin/rto-worklist")
class AdminRtoWorklistController {

    private static final String STATION_MANAGER = "STATION_MANAGER";

    private final RtoWorklistService worklistService;

    AdminRtoWorklistController(RtoWorklistService worklistService) {
        this.worklistService = worklistService;
    }

    @GetMapping
    public List<RtoWorklistItem> open(@AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, STATION_MANAGER);
        return worklistService.listOpen(cityScope(principal));
    }

    @PostMapping("/{id}/done")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markDone(@AuthenticationPrincipal AuthUserDetails principal,
                         @PathVariable("id") UUID id) {
        Authz.requireRole(principal, STATION_MANAGER);
        worklistService.markDone(id, cityScope(principal), Authz.requireUserId(principal));
    }

    /** Null for ADMIN (all cities); the station manager's own city otherwise (403 if unassigned). */
    private static String cityScope(AuthUserDetails principal) {
        if (!STATION_MANAGER.equals(principal.getUser().getRole().getName())) {
            return null;
        }
        String cityScope = principal.getUser().getCityId();
        if (cityScope == null || cityScope.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Station manager has no city assigned");
        }
        return cityScope;
    }
}
