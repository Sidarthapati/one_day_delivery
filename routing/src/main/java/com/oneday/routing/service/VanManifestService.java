package com.oneday.routing.service;

import com.oneday.routing.service.model.BindOutcome;
import com.oneday.routing.service.model.BindingResult;

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
}
