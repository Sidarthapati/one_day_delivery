package com.oneday.orders.dto;

import java.util.List;
import java.util.UUID;

/**
 * Per-item outcome of a checkout. Successfully booked items carry a {@code shipmentRef};
 * failed items carry a reason and remain in the (still OPEN) cart for the user to fix.
 */
public record CartCheckoutResponse(
        int booked,
        int failed,
        long chargedTotalPaise,
        String cartStatus,
        List<Result> results
) {
    public record Result(UUID cartItemId, boolean success, String shipmentRef, String reason) {
        public static Result ok(UUID id, String ref) { return new Result(id, true, ref, null); }
        public static Result fail(UUID id, String reason) { return new Result(id, false, null, reason); }
    }
}
