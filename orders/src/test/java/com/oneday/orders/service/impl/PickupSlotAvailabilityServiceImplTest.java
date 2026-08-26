package com.oneday.orders.service.impl;

import com.oneday.common.domain.PickupSlots;
import com.oneday.common.domain.enums.PickupType;
import com.oneday.orders.config.PickupSlotProperties;
import com.oneday.orders.dto.SlotAvailabilityResponse;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.repository.SlotBookingCount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PickupSlotAvailabilityServiceImplTest {

    @Mock private ShipmentRepository shipments;

    private PickupSlotAvailabilityServiceImpl service(int maxPerSlot) {
        PickupSlotProperties props = new PickupSlotProperties();
        props.setMaxPerSlot(maxPerSlot);
        return new PickupSlotAvailabilityServiceImpl(shipments, props);
    }

    private static SlotBookingCount count(Instant start, long n) {
        return new SlotBookingCount() {
            public Instant getStart() { return start; }
            public long getCount() { return n; }
        };
    }

    @Test
    void reportsRemainingPerSlot_andMarksTheFullOne() {
        LocalDate today = LocalDate.now(PickupSlots.ZONE);
        // Slot 09:00 today is fully booked (10 of 10); everything else empty.
        Instant nineToday = PickupSlots.resolve(today, 9).start();
        when(shipments.countBookedSlotsInRange(eq("DEL"), eq(PickupType.DA_PICKUP), any(), any()))
                .thenReturn(List.of(count(nineToday, 10)));

        SlotAvailabilityResponse r = service(10).forCity("del", 2);  // lowercase → normalised

        assertThat(r.city()).isEqualTo("DEL");
        // 2 days × 7 slot hours
        assertThat(r.slots()).hasSize(14);
        var full = r.slots().stream().filter(SlotAvailabilityResponse.Slot::full).toList();
        assertThat(full).hasSize(1);
        assertThat(full.get(0).date()).isEqualTo(today);
        assertThat(full.get(0).startHour()).isEqualTo(9);
        assertThat(full.get(0).remaining()).isZero();
        // an untouched slot has the full capacity remaining
        var elevenToday = r.slots().stream()
                .filter(s -> s.date().equals(today) && s.startHour() == 11).findFirst().orElseThrow();
        assertThat(elevenToday.remaining()).isEqualTo(10);
        assertThat(elevenToday.full()).isFalse();
    }

    @Test
    void clampsDaysToSaneBounds() {
        when(shipments.countBookedSlotsInRange(any(), any(), any(), any())).thenReturn(List.of());
        assertThat(service(50).forCity("BLR", 0).slots()).hasSize(7);    // min 1 day
        assertThat(service(50).forCity("BLR", 999).slots()).hasSize(14 * 7); // capped at 14 days
    }
}
