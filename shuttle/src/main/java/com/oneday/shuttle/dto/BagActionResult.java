package com.oneday.shuttle.dto;

import java.util.List;
import java.util.UUID;

/**
 * Per-bag outcome of a batched "Out to airport". A bag another agent already took (or the hub hasn't
 * sealed) is reported {@code skipped} rather than failing the whole trip.
 */
public record BagActionResult(List<BagOutcome> results) {

    public record BagOutcome(UUID bagId, String outcome, int parcelsAdvanced) {
        public static BagOutcome dispatched(UUID bagId, int parcels) {
            return new BagOutcome(bagId, "DISPATCHED", parcels);
        }

        public static BagOutcome skipped(UUID bagId, String reason) {
            return new BagOutcome(bagId, "SKIPPED:" + reason, 0);
        }
    }
}
