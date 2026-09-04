package com.oneday.dispatch.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.dispatch.dto.response.DaIntegritySummary;
import com.oneday.dispatch.service.DaIntegrityService;
import com.oneday.grid.service.GridService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Ops location-trust console (M5 anti-abuse Phase 6). Lists each DA's GREEN/AMBER/RED trust standing
 * for a date so a station manager can spot a GPS spoofer before payroll settles. STATION_MANAGER sees
 * their own city; ADMIN sees all. Read-only — holding payroll stays a human decision.
 */
@RestController
public class DaIntegrityController {

    private final DaIntegrityService daIntegrityService;
    private final GridService gridService;

    public DaIntegrityController(DaIntegrityService daIntegrityService, GridService gridService) {
        this.daIntegrityService = daIntegrityService;
        this.gridService = gridService;
    }

    @GetMapping("/dispatch/integrity/das")
    public List<DaIntegritySummary> das(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.STATION_MANAGER);
        UUID scopeCityId = Authz.isAdmin(principal) ? null : managerCity(principal);
        return daIntegrityService.summariesForDate(date != null ? date : LocalDate.now(), scopeCityId);
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
