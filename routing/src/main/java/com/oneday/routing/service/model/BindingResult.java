package com.oneday.routing.service.model;

import java.util.List;
import java.util.UUID;

// Outcome of binding a batch of parcels to loops.
public record BindingResult(List<ParcelBinding> bound, List<ParcelBinding> overflowed, List<ParcelBinding> unresolved) {

    public enum Outcome { BOUND, OVERFLOW, UNRESOLVED }

    // One parcel's result; loopIndex/vanId/manifestItemId set only when BOUND.
    public record ParcelBinding(UUID parcelId, Outcome outcome, Integer loopIndex, UUID vanId, UUID manifestItemId) {
        public static ParcelBinding bound(UUID parcelId, int loopIndex, UUID vanId, UUID itemId) {
            return new ParcelBinding(parcelId, Outcome.BOUND, loopIndex, vanId, itemId);
        }
        public static ParcelBinding overflow(UUID parcelId, UUID vanId) {
            return new ParcelBinding(parcelId, Outcome.OVERFLOW, null, vanId, null);
        }
        public static ParcelBinding unresolved(UUID parcelId) {
            return new ParcelBinding(parcelId, Outcome.UNRESOLVED, null, null, null);
        }
    }
}
