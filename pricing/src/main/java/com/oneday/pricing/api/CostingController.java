package com.oneday.pricing.api;

import com.oneday.common.port.dto.CostFloorQuery;
import com.oneday.common.port.dto.CostFloorResult;
import com.oneday.pricing.service.CostingService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal per-parcel cost floor (M2-D-004). ADMIN only — this figure is never customer-facing.
 */
@RestController
@RequestMapping("/api/v1/pricing")
public class CostingController {

    private final CostingService costing;

    public CostingController(CostingService costing) {
        this.costing = costing;
    }

    @GetMapping("/cost-floor")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CostFloorResult> costFloor(@RequestParam String city) {
        return ResponseEntity.ok(costing.computeCostFloor(new CostFloorQuery(city)));
    }
}
