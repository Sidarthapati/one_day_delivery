package com.oneday.dispatch.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.dispatch.dto.response.TileQueueResponse;
import com.oneday.dispatch.service.StationDispatchService;
import com.oneday.grid.service.GridService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final GridService gridService;

    public StationDispatchController(StationDispatchService stationDispatchService, GridService gridService) {
        this.stationDispatchService = stationDispatchService;
        this.gridService = gridService;
    }

    @GetMapping("/dispatch/tiles/{tileId}/queue")
    public TileQueueResponse tileQueue(@PathVariable UUID tileId,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                       @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.STATION_MANAGER);
        UUID scopeCityId = Authz.isAdmin(principal) ? null : managerCity(principal);
        return stationDispatchService.tileQueue(tileId, date, scopeCityId);
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
