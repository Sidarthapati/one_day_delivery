package com.oneday.dispatch.domain;

/**
 * Why a {@code RETURN_TO_HUB} carry-back task exists. It changes what happens when the DA scans the
 * parcel back in at the hub ({@code DaTaskService.recordReturnedToHub}):
 *
 * <ul>
 *   <li>{@code DELIVERY_FAILURE} / {@code RESCHEDULE} — the delivery-failure / receiver-reschedule
 *       carry-back. The parcel sits in hub custody and the deferred-retry engine reassigns it to a
 *       fresh DA (ledger-only {@code HUB_RETURN_IN} scan).</li>
 *   <li>{@code SHIFT_CLOSE} (SC1) — the shift-close carry-back. A ledger-only {@code HUB_SHIFT_RETURN_IN}
 *       scan records the return; the physical re-sort into a territory bag then happens when the hub
 *       dock-receives the parcel ({@code HubReceivingService.receive}), exactly as any dest arrival.</li>
 * </ul>
 */
public enum CarryBackReason {
    DELIVERY_FAILURE,
    RESCHEDULE,
    SHIFT_CLOSE
}
