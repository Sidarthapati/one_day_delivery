package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.dto.AdminOrderDetailResponse;
import com.oneday.orders.dto.OrderPageResponse;
import com.oneday.orders.service.AdminOrderQueryService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The business/admin console's order view (Order → N Shipments) — one card per booking that expands
 * to its parcels. The order-level counterpart to {@link AdminOrdersController} (which lists individual
 * shipments). Same visibility model: ADMIN sees every city; a STATION_MANAGER sees orders placed in
 * their own city.
 */
@RestController
@RequestMapping("/api/v1/admin/orders")
class AdminOrderGroupsController {

    private static final String STATION_MANAGER = "STATION_MANAGER";

    private final AdminOrderQueryService adminOrderQueryService;

    AdminOrderGroupsController(AdminOrderQueryService adminOrderQueryService) {
        this.adminOrderQueryService = adminOrderQueryService;
    }

    @GetMapping
    public OrderPageResponse listOrders(
            @AuthenticationPrincipal AuthUserDetails principal,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size) {
        Authz.requireRole(principal, STATION_MANAGER);
        return adminOrderQueryService.listOrders(cityScope(principal), page, size);
    }

    @GetMapping("/{orderRef}")
    public AdminOrderDetailResponse orderDetail(
            @AuthenticationPrincipal AuthUserDetails principal,
            @PathVariable("orderRef") String orderRef) {
        Authz.requireRole(principal, STATION_MANAGER);
        return adminOrderQueryService.orderDetail(orderRef, cityScope(principal));
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
