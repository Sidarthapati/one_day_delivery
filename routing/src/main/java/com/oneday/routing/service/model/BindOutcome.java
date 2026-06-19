package com.oneday.routing.service.model;

import java.util.List;
import java.util.UUID;

// Result of binding one parcel. loopIndex/vanId/manifestItemId set only when BOUND; bumped lists any
// already-bound parcels displaced to a later loop to make room (reactive bump, §12.3/§12.4).
public record BindOutcome(UUID parcelId, Outcome outcome, Integer loopIndex, UUID vanId,
                          UUID manifestItemId, List<UUID> bumped) {

    public enum Outcome { BOUND, OVERFLOW, UNRESOLVED }

    public static BindOutcome bound(UUID parcelId, Integer loopIndex, UUID vanId, UUID itemId, List<UUID> bumped) {
        return new BindOutcome(parcelId, Outcome.BOUND, loopIndex, vanId, itemId, bumped);
    }

    public static BindOutcome overflow(UUID parcelId, UUID vanId) {
        return new BindOutcome(parcelId, Outcome.OVERFLOW, null, vanId, null, List.of());
    }

    public static BindOutcome unresolved(UUID parcelId) {
        return new BindOutcome(parcelId, Outcome.UNRESOLVED, null, null, null, List.of());
    }

    public boolean isBound() {
        return outcome == Outcome.BOUND;
    }
}
