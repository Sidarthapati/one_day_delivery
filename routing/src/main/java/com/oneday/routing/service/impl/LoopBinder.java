package com.oneday.routing.service.impl;

import com.oneday.routing.service.model.LoopSlot;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.IntPredicate;

// SLA-first, capacity-bounded loop choice (§12.3, M6-D-016). Pure: no DB, no clock.
final class LoopBinder {

    private LoopBinder() {}

    // Deliver: feasible loops (vanArrival + daDelivery ≤ deadline) earliest-first — the per-parcel
    // binder walks these and stops at the first with live capacity.
    static List<LoopSlot> feasibleDeliverLoopsAsc(List<LoopSlot> slots, Instant deadline, Duration daDelivery) {
        return slots.stream()
                .filter(s -> !s.vanArrival().plus(daDelivery).isAfter(deadline))
                .sorted(Comparator.comparingInt(LoopSlot::loopIndex))
                .toList();
    }

    // Collect: feasible loops (hubReturn ≤ deadline) latest-first.
    static List<LoopSlot> feasibleCollectLoopsDesc(List<LoopSlot> slots, Instant deadline) {
        return slots.stream()
                .filter(s -> !s.hubReturn().isAfter(deadline))
                .sorted(Comparator.comparingInt(LoopSlot::loopIndex).reversed())
                .toList();
    }

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
