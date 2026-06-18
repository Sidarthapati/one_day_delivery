package com.oneday.routing.service.impl;

import com.oneday.routing.service.model.LoopSlot;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.IntPredicate;

// SLA-first, capacity-bounded loop choice (§12.3, M6-D-016). Pure: no DB, no clock.
final class LoopBinder {

    private LoopBinder() {}

    // Deliver (§12.1): earliest loop where vanArrival + daDelivery ≤ deadline and the loop has room.
    static OptionalInt earliestDeliverLoop(List<LoopSlot> slots, Instant deadline,
                                           Duration daDelivery, IntPredicate hasCapacity) {
        return slots.stream()
                .filter(s -> !s.vanArrival().plus(daDelivery).isAfter(deadline))
                .filter(s -> hasCapacity.test(s.loopIndex()))
                .mapToInt(LoopSlot::loopIndex)
                .min();
    }

    // Collect (§12.2): latest loop whose hub return ≤ deadline and that still has room.
    static OptionalInt latestCollectLoop(List<LoopSlot> slots, Instant deadline, IntPredicate hasCapacity) {
        return slots.stream()
                .filter(s -> !s.hubReturn().isAfter(deadline))
                .filter(s -> hasCapacity.test(s.loopIndex()))
                .mapToInt(LoopSlot::loopIndex)
                .max();
    }
}
