package com.oneday.routing.service.impl;

import com.oneday.routing.service.model.LoopSlot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LoopBinderTest {

    private static final UUID VAN = UUID.randomUUID();
    private static final UUID VERTEX = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-06-18T02:00:00Z"); // 07:30 IST

    private LoopSlot slot(int loop) {
        Instant arrival = T0.plus(Duration.ofHours(loop));
        return new LoopSlot(loop, VAN, VERTEX, loop + 1, arrival, arrival.plus(Duration.ofMinutes(30)));
    }

    @Test
    void ordersLoopsEarliestFirst_regardlessOfInputOrder() {
        List<Integer> order = LoopBinder.loopsEarliestFirst(List.of(slot(2), slot(0), slot(1)))
                .stream().map(LoopSlot::loopIndex).toList();
        assertThat(order).containsExactly(0, 1, 2);
    }

    @Test
    void emptyWhenNoSlots() {
        assertThat(LoopBinder.loopsEarliestFirst(List.of())).isEmpty();
    }
}
