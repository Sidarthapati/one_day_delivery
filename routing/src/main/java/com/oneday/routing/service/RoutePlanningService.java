package com.oneday.routing.service;

import com.oneday.routing.domain.RoutePlan;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Orchestrates the nightly planning pipeline for one city/date (§7): demand → meeting points →
 * travel matrix → VRP solve → periodise → persist. Produces a PROPOSED {@link RoutePlan} plus its
 * {@code route_plan_stop} rows and per-DA {@code da_cron_schedule}, and runs the fleet-sizing pass
 * that flags {@code UNDER_PROVISIONED} (M6-D-003/-005/-008). Approval/override/events are §10 (PR #4).
 */
public interface RoutePlanningService {

    /** Plan the city's van loops for {@code date}; persists and returns the PROPOSED plan. */
    RoutePlan plan(UUID cityId, LocalDate date);
}
