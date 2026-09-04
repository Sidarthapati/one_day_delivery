package com.oneday.dispatch.service;

import com.oneday.dispatch.dto.response.DaIntegritySummary;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Ops-facing rollup of DA location-trust for a date. Reads the per-ping risk signals recorded on the
 * breadcrumb and triages each DA GREEN/AMBER/RED so a station manager can spot a spoofer before payroll
 * settles. Read-only — enforcement (holding payroll) stays a human decision in the hybrid posture.
 */
public interface DaIntegrityService {

    /**
     * Per-DA trust standing for {@code date}. {@code scopeCityId} null = all cities (ADMIN); otherwise
     * only DAs in that city (station manager). Ordered worst-trust first.
     */
    List<DaIntegritySummary> summariesForDate(LocalDate date, UUID scopeCityId);
}
