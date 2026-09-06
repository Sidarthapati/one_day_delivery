package com.oneday.dispatch.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * What the driver app shows in the morning: the deadline-aware windows in which a break is allowed
 * today, the daily allowance and how much is left, the minimum break length, and the DA's currently
 * active disposition (if any) so the card can render a countdown / "I'm back".
 */
public record DispositionSlotsResponse(
        List<BreakSlot> slots,
        int dailyAllowanceMinutes,
        int remainingAllowanceMinutes,
        int minBreakMinutes,
        DispositionResponse active) {

    /** A contiguous window [start, end) in which a break may be taken. */
    public record BreakSlot(Instant start, Instant end) {
    }
}
