package com.oneday.dispatch.dto.response;

import java.util.UUID;

/**
 * One DA's shift-close standing — the SC1 pane of the station's Shift Close console. A DA appears here
 * only if they have something outstanding today: a parcel still in hand, or a shift-close carry-back in
 * flight. A DA who delivered everything (or was never assigned) isn't listed — nothing to reconcile.
 *
 * @param inHandDeliveries still physically holding — an IN_PROGRESS DELIVERY not yet carried back
 * @param carryBacksPending shift-close RETURN_TO_HUB tasks not yet scanned in at the hub
 * @param carryBacksDone    shift-close RETURN_TO_HUB tasks completed (parcel back at the hub, re-sorted)
 * @param reconciled        true when nothing is outstanding: no in-hand delivery and no pending carry-back
 */
public record ShiftCloseReconciliation(
        UUID daId,
        String daName,
        String daPhone,
        int inHandDeliveries,
        int carryBacksPending,
        int carryBacksDone,
        boolean reconciled) {
}
