package com.oneday.routing.service.impl;

import com.oneday.routing.service.model.LoopSlot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.IntPredicate;

import static org.assertj.core.api.Assertions.assertThat;

class LoopBinderTest {

    private static final UUID VAN = UUID.randomUUID();
    private static final UUID VERTEX = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-06-18T02:00:00Z"); // 07:30 IST
    private static final IntPredicate ROOM = l -> true;

    // arrival = T0 + loop hours; hubReturn = T0 + loop hours + 30m.
    private LoopSlot slot(int loop) {
        Instant arrival = T0.plus(Duration.ofHours(loop));
        return new LoopSlot(loop, VAN, VERTEX, loop + 1, arrival, arrival.plus(Duration.ofMinutes(30)));
    }

    @Test
    void deliver_picksEarliestFeasibleLoop() {
        List<LoopSlot> slots = List.of(slot(0), slot(1), slot(2));
        Instant deadline = T0.plus(Duration.ofHours(5));
        OptionalInt loop = LoopBinder.earliestDeliverLoop(slots, deadline, Duration.ofMinutes(30), ROOM);
        assertThat(loop).hasValue(0);
    }

    @Test
    void deliver_excludesLoopsPastDeadline() {
        List<LoopSlot> slots = List.of(slot(0), slot(1), slot(2));
        // deadline only loop 0 makes once daDelivery (30m) is added: arrival 07:30 + 30m = 08:00.
        Instant deadline = T0.plus(Duration.ofMinutes(45));
        OptionalInt loop = LoopBinder.earliestDeliverLoop(slots, deadline, Duration.ofMinutes(30), ROOM);
        assertThat(loop).hasValue(0);
    }

    @Test
    void deliver_skipsFullLoopAndTakesNext() {
        List<LoopSlot> slots = List.of(slot(0), slot(1), slot(2));
        Instant deadline = T0.plus(Duration.ofHours(5));
        IntPredicate loop0Full = l -> l != 0;
        OptionalInt loop = LoopBinder.earliestDeliverLoop(slots, deadline, Duration.ofMinutes(30), loop0Full);
        assertThat(loop).hasValue(1);
    }

    @Test
    void deliver_emptyWhenNoLoopBeatsDeadline() {
        List<LoopSlot> slots = List.of(slot(0), slot(1));
        Instant deadline = T0.minus(Duration.ofMinutes(1)); // before any arrival
        OptionalInt loop = LoopBinder.earliestDeliverLoop(slots, deadline, Duration.ofMinutes(30), ROOM);
        assertThat(loop).isEmpty();
    }

    @Test
    void collect_picksLatestLoopThatStillMakesTheCutoff() {
        List<LoopSlot> slots = List.of(slot(0), slot(1), slot(2));
        // hubReturn(loop2) = T0 + 2h30m; deadline at 2h45m admits loops 0,1,2 → latest = 2.
        Instant deadline = T0.plus(Duration.ofHours(2)).plus(Duration.ofMinutes(45));
        OptionalInt loop = LoopBinder.latestCollectLoop(slots, deadline, ROOM);
        assertThat(loop).hasValue(2);
    }

    @Test
    void collect_excludesLoopsReturningAfterCutoff() {
        List<LoopSlot> slots = List.of(slot(0), slot(1), slot(2));
        // deadline at 1h45m: loop0 (30m) + loop1 (1h30m) make it, loop2 (2h30m) does not → latest = 1.
        Instant deadline = T0.plus(Duration.ofHours(1)).plus(Duration.ofMinutes(45));
        OptionalInt loop = LoopBinder.latestCollectLoop(slots, deadline, ROOM);
        assertThat(loop).hasValue(1);
    }
}
