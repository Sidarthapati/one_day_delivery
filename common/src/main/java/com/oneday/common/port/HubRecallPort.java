package com.oneday.common.port;

import java.util.UUID;

/**
 * Origin-side physical recall for a mid-transit RTO. When a return is requested while a parcel is
 * still at the origin hub, we want to pull it out of its flight bag <em>before it flies</em> and send
 * it back to the sender within the origin city (cheap, no flight) — but only while the bag is still
 * {@code OPEN}. Once the bag is {@code SEALED} the manifest is generated and the AWB is booked, so the
 * parcel must fly to the destination hub and RTO from there.
 *
 * <p>Implemented in the {@code hub} module (M7); injected into {@code orders} (M4) as a port so the
 * lower-level module never imports the hub. Append-only: a recall flips the flight-bag item to
 * {@code REMOVED}, never unseals a bag.</p>
 */
public interface HubRecallPort {

    enum RecallOutcome {
        /** The parcel was in an OPEN bag and was pulled out — it must be physically fished from that bag. */
        PULLED_FROM_BAG,
        /** The parcel was not bagged yet — safe to return from origin now, nothing to pull. */
        NOT_BAGGED,
        /** The parcel's bag is already SEALED/DISPATCHED — it must fly; RTO from the destination hub. */
        COMMITTED;

        /** Both non-committed outcomes are recallable at origin (same-city return now). */
        public boolean isRecalled() {
            return this != COMMITTED;
        }
    }

    /**
     * Attempt to recall a parcel from its origin flight bag. Idempotent: a parcel not currently in an
     * {@code IN_BAG} item is {@link RecallOutcome#NOT_BAGGED} (nothing to pull).
     *
     * @param shipmentId the shipment (== flight-bag item {@code parcel_id}) to recall
     */
    RecallOutcome recallAtOrigin(UUID shipmentId);
}
