package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.dto.CancellationResponse;
import com.oneday.orders.dto.ShipmentPageResponse;
import com.oneday.orders.service.AdminOrderQueryService;
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
 * Read-only orders-database access. The counterpart to the customer booking endpoints — these
 * roles cannot book (see {@link Authz#requireCustomerRole}) but can read shipments:
 * <ul>
 *   <li><b>ADMIN</b> — oversight across every city.</li>
 *   <li><b>STATION_MANAGER</b> — scoped to their own city: every shipment whose origin OR
 *       destination is that city (the custody model's "X, Y can read" rule). Each row also carries
 *       {@code custody_city} + {@code can_act} so the manager sees which parcels they currently own.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/shipments")
class AdminOrdersController {

    private static final String STATION_MANAGER = "STATION_MANAGER";

    private final AdminOrderQueryService adminOrderQueryService;
    private final CancellationService cancellationService;

    AdminOrdersController(AdminOrderQueryService adminOrderQueryService,
                          CancellationService cancellationService) {
        this.adminOrderQueryService = adminOrderQueryService;
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

        // Admin sees all cities (null scope). A station manager is restricted to their own city.
        String cityScope = null;
        if (STATION_MANAGER.equals(principal.getUser().getRole().getName())) {
            cityScope = principal.getUser().getCityId();
            if (cityScope == null || cityScope.isBlank()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Station manager has no city assigned");
            }
        }
        return adminOrderQueryService.listShipments(state, cityScope, page, size);
    }

    /**
     * Admin row-action cancel from the orders database. ADMIN only — privileged cancellation that
     * works on either lane and skips the B2B account-ownership check (admin acts on behalf). The
     * cancellation cutoff still applies (→ 409 if past it).
     */
    @DeleteMapping("/{ref}")
    public CancellationResponse cancelShipment(
            @AuthenticationPrincipal AuthUserDetails principal,
            @PathVariable("ref") String ref,
            @RequestParam(value = "reason", required = false) String reason) {
        Authz.requireRole(principal);   // ADMIN only (no other allowed roles)
        return cancellationService.cancelAsAdmin(ref, reason, Authz.requireUserId(principal));
    }
}
