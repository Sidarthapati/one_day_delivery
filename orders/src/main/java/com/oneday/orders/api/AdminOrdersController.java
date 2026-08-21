package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.dto.CancellationResponse;
import com.oneday.orders.dto.ShipmentPageResponse;
import com.oneday.orders.dto.ShipmentSummaryStats;
import com.oneday.orders.service.AdminOrderQueryService;
import com.oneday.orders.service.AdminOrderSummaryService;
import com.oneday.orders.service.CancellationService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Orders-database access for ops tooling. The counterpart to the customer booking endpoints — these
 * roles cannot book (see {@link Authz#requireCustomerRole}) but can read, and now also cancel:
 * <ul>
 *   <li><b>ADMIN</b> — reads every city; cancels any shipment, any lane.</li>
 *   <li><b>STATION_MANAGER</b> — reads every shipment whose origin OR destination is their own city
 *       (the custody model's "X, Y can read" rule; each row carries {@code custody_city} +
 *       {@code can_act} so the manager sees which parcels they currently own). Cancel is narrower
 *       than read: only a shipment whose <em>current</em> custodian is their city.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/shipments")
class AdminOrdersController {

    private static final String STATION_MANAGER = "STATION_MANAGER";

    private final AdminOrderQueryService adminOrderQueryService;
    private final AdminOrderSummaryService adminOrderSummaryService;
    private final CancellationService cancellationService;

    AdminOrdersController(AdminOrderQueryService adminOrderQueryService,
                          AdminOrderSummaryService adminOrderSummaryService,
                          CancellationService cancellationService) {
        this.adminOrderQueryService = adminOrderQueryService;
        this.adminOrderSummaryService = adminOrderSummaryService;
        this.cancellationService = cancellationService;
    }

    @GetMapping
    public ShipmentPageResponse listShipments(
            @AuthenticationPrincipal AuthUserDetails principal,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size) {
        // ADMIN (always) + STATION_MANAGER; everyone else → 403.
        Authz.requireRole(principal, STATION_MANAGER);
        return adminOrderQueryService.listShipments(state, cityScope(principal), page, size);
    }

    /**
     * Aggregate parcel counts for the ops monitoring dashboard — per ops bucket and per raw state.
     * Same visibility as {@link #listShipments}: ADMIN counts every city (null scope), a
     * STATION_MANAGER counts only shipments touching their own city.
     */
    @GetMapping("/summary")
    public ShipmentSummaryStats summary(@AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, STATION_MANAGER);
        return adminOrderSummaryService.summary(cityScope(principal));
    }

    /** Null for ADMIN (all cities); the station manager's own city otherwise (403 if unassigned). */
    private static String cityScope(AuthUserDetails principal) {
        if (!STATION_MANAGER.equals(principal.getUser().getRole().getName())) {
            return null;
        }
        String cityScope = principal.getUser().getCityId();
        if (cityScope == null || cityScope.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Station manager has no city assigned");
        }
        return cityScope;
    }

    /**
     * Admin row-action cancel from the orders database. ADMIN cancels any shipment, any lane, no
     * ownership check. STATION_MANAGER may also cancel, but only a shipment currently in their own
     * city's custody ({@link com.oneday.orders.service.ShipmentCustody}) — a ref outside that scope
     * 404s rather than 403ing, matching the b2b/b2c lane guard elsewhere in this module. The
     * cancellation cutoff still applies either way (→ 409 if past it).
     */
    @DeleteMapping("/{ref}")
    public CancellationResponse cancelShipment(
            @AuthenticationPrincipal AuthUserDetails principal,
            @PathVariable("ref") String ref,
            @RequestParam(value = "reason", required = false) String reason) {
        Authz.requireRole(principal, STATION_MANAGER);
        String userId = Authz.requireUserId(principal);

        if (STATION_MANAGER.equals(principal.getUser().getRole().getName())) {
            String cityScope = principal.getUser().getCityId();
            if (cityScope == null || cityScope.isBlank()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Station manager has no city assigned");
            }
            return cancellationService.cancelAsStationManager(ref, reason, userId, cityScope);
        }
        return cancellationService.cancelAsAdmin(ref, reason, userId);
    }
}
