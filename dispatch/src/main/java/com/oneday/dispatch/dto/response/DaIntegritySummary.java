package com.oneday.dispatch.dto.response;

import java.util.UUID;

/**
 * One DA's location-trust standing for a date, for the ops integrity console. {@code trustLevel} is a
 * GREEN/AMBER/RED triage: RED = a mock-provider fix or a high-risk fix (review payroll); AMBER = some
 * flagged fixes; GREEN = clean.
 */
public record DaIntegritySummary(
        UUID daId,
        String daName,
        UUID cityId,
        long totalPings,
        long flaggedPings,
        int maxRiskScore,
        long mockedPings,
        long velocityPings,
        long skewPings,
        String trustLevel) {
}
