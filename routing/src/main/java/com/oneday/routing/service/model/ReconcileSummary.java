package com.oneday.routing.service.model;

// Outcome of reconciling a day's manifests onto a freshly-approved plan after an intraday re-plan
// (§10.x). repointedManifests: rows moved from a superseded plan to the live one; reboundPlanned:
// not-yet-loaded items re-routed against the new plan; keptInCustody: loaded/terminal items left
// untouched whose stop survived; escalated: loaded items whose stop vanished, raised to ops (frozen).
public record ReconcileSummary(int repointedManifests, int reboundPlanned, int keptInCustody, int escalated) {

    public static ReconcileSummary empty() {
        return new ReconcileSummary(0, 0, 0, 0);
    }
}
