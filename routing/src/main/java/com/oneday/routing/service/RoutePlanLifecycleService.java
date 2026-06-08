package com.oneday.routing.service;

import com.oneday.routing.domain.DaCronSchedule;
import com.oneday.routing.domain.RoutePlan;
import com.oneday.routing.domain.RoutePlanStop;
import com.oneday.routing.dto.OverrideRequest;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Nightly governance (§10): approve a PROPOSED plan, override a live one with an append-only
 * revision, force a re-solve, and the read side that serves the §17.2 query endpoints. All state
 * changes are append-only (C17): revisions supersede; plan bodies never mutate.
 */
public interface RoutePlanLifecycleService {

    /** Station manager / admin approves a PROPOSED plan → APPROVED; emits DA_CRON_SCHEDULED + publish. */
    RoutePlan approve(UUID planId, UUID actorId);

    /** Append-only override of a live plan: new revision, audit row, ROUTE_CHANGED. */
    RoutePlan override(UUID planId, OverrideRequest request);

    /** Force a re-solve for {@code cityId}/{@code date}: supersede current plans, produce a new PROPOSED one. */
    RoutePlan replan(UUID cityId, LocalDate date);

    /** The live plan for a city/date — highest-revision APPROVED, else PROPOSED (ignores SUPERSEDED/REJECTED). */
    Optional<RoutePlan> activePlan(UUID cityId, LocalDate date);

    List<RoutePlanStop> stops(UUID planId, UUID vanId);

    List<DaCronSchedule> cronForDa(UUID daId, LocalDate date);
}
