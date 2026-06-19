package com.oneday.routing.service;

import com.oneday.routing.service.model.RecoverySummary;

import java.time.LocalDate;
import java.util.UUID;

// Intraday failure recovery (§13.4/§13.5, M6-D-021). The only sanctioned intraday fleet change (C18):
// when a van breaks down, a recovery van takes over its open manifest items; a DA no-show carries
// that DA's undelivered parcels onto their next feasible loop.
public interface RecoveryService {

    // Van breakdown: emit VAN_BREAKDOWN and move the broken van's in-flight items to the recovery van.
    RecoverySummary recoverVan(UUID brokenVanId, UUID recoveryVanId, UUID cityId, LocalDate date,
                               double lastLat, double lastLon);

    // DA no-show at a stop: carry that DA's still-undelivered parcels to their next loop; collections
    // are deferred (the next pickup event re-binds them).
    int carryNoShow(UUID vanId, int loopIndex, LocalDate date, int stopSeq, UUID daId);
}
