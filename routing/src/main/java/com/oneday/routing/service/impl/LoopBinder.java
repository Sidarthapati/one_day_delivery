package com.oneday.routing.service.impl;

import com.oneday.routing.service.model.LoopSlot;

import java.util.Comparator;
import java.util.List;

// v1 fastest-greedy loop choice (§12.3, M6-D-016). BOTH directions bind the SOONEST loop with room:
// a freshly-sorted delivery rides the next van out; a just-collected parcel rides the next van back.
// The SLA deadline / flight cutoff is advisory — stored on the manifest item for M10/M9 to act on,
// never a gate, so a late (or deadline-less) parcel still moves instead of being dropped. Pure: no
// DB, no clock. Overflow is decided by the caller and means only "no loop had capacity".
final class LoopBinder {

    private LoopBinder() {}

    static List<LoopSlot> loopsEarliestFirst(List<LoopSlot> slots) {
        return slots.stream()
                .sorted(Comparator.comparingInt(LoopSlot::loopIndex))
                .toList();
    }
}
