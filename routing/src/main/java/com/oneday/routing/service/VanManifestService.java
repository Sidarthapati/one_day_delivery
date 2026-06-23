package com.oneday.routing.service;

import com.oneday.routing.service.model.BindOutcome;
import com.oneday.routing.service.model.BindingResult;
import com.oneday.routing.service.model.ReconcileSummary;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// Manifest engine: binds real parcels to van loops against the locked plan (§11.2, §12).
// Primary path is per-parcel on event arrival; the reconcile* sweeps are the backstop.
public interface VanManifestService {

    // Bind one sorted-for-delivery parcel to its earliest deadline-feasible loop, bumping a slacker
    // already-bound parcel if that loop is full (§12.1/§12.3). Idempotent on re-delivery.
    BindOutcome bindDelivery(UUID cityId, LocalDate date, UUID parcelId, UUID destinationHexId, Instant slaDeadline);

    // Bind one DA-collected parcel to its latest flight-feasible loop (§12.2). Idempotent on re-delivery.
    BindOutcome bindCollect(UUID cityId, LocalDate date, UUID parcelId, UUID daId);

    // Backstop sweep over the accumulation buffer; binds anything the event path missed, SLA-first.
    BindingResult reconcileDeliveries(UUID cityId, LocalDate date);

    BindingResult reconcileCollections(UUID cityId, LocalDate date);

    // Carry a delivery the DA didn't take (no-show / late, §13.2) onto its next feasible loop: the
    // current item is marked EXCEPTION and the parcel re-bound to a later loop, else overflow.
    BindOutcome rebindDelivery(UUID parcelId);

    // Intraday re-plan safety net (Option B + PLANNED reconciliation, §10.x). When a new plan becomes
    // the live APPROVED plan for the day, any manifest still pointing at a superseded plan is re-pointed
    // to the live one (the physical van/loop is unchanged); its not-yet-loaded (PLANNED) items are
    // re-routed against the new plan; loaded/terminal items whose stop survives are kept; a loaded item
    // whose stop vanished is escalated (LOOP_OVERFLOW) and left frozen (it is physically on the van).
    // No-op when there is one plan per day (nothing bound to a stale plan), so it is safe to always call.
    ReconcileSummary reconcileToLivePlan(UUID cityId, LocalDate date);
}
