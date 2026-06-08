package com.oneday.routing.dto;

import com.oneday.routing.domain.RoutePlan;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Plan header + fleet-sizing verdict ({@code GET /routing/plans/{cityId}}). */
public record RoutePlanResponse(
        UUID planId,
        UUID cityId,
        LocalDate validForDate,
        String status,
        String source,
        String solverType,
        int revision,
        UUID supersedesPlanId,
        Integer vansUsed,
        Integer recommendedVanCount,
        String provisioningFlag,
        Integer nLoops,
        Integer realisedCycleMinutes,
        String notes,
        UUID approvedBy,
        Instant approvedAt,
        Instant createdAt) {

    public static RoutePlanResponse from(RoutePlan p) {
        return new RoutePlanResponse(
                p.getId(), p.getCityId(), p.getValidForDate(),
                p.getStatus() != null ? p.getStatus().name() : null,
                p.getSource() != null ? p.getSource().name() : null,
                p.getSolverType() != null ? p.getSolverType().name() : null,
                p.getRevision(), p.getSupersedesPlanId(), p.getVansUsed(), p.getRecommendedVanCount(),
                p.getProvisioningFlag() != null ? p.getProvisioningFlag().name() : null,
                p.getNLoops(), p.getRealisedCycleMinutes(), p.getNotes(),
                p.getApprovedBy(), p.getApprovedAt(), p.getCreatedAt());
    }
}
