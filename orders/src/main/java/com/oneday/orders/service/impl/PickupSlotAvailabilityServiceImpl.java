package com.oneday.orders.service.impl;

import com.oneday.common.domain.PickupSlots;
import com.oneday.common.domain.enums.PickupType;
import com.oneday.orders.config.PickupSlotProperties;
import com.oneday.orders.dto.SlotAvailabilityResponse;
import com.oneday.orders.dto.SlotAvailabilityResponse.Slot;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.repository.SlotBookingCount;
import com.oneday.orders.service.PickupSlotAvailabilityService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
class PickupSlotAvailabilityServiceImpl implements PickupSlotAvailabilityService {

    private static final int MAX_DAYS = 14;

    private final ShipmentRepository shipments;
    private final PickupSlotProperties props;

    PickupSlotAvailabilityServiceImpl(ShipmentRepository shipments, PickupSlotProperties props) {
        this.shipments = shipments;
        this.props = props;
    }

    @Override
    @Transactional(readOnly = true)
    public SlotAvailabilityResponse forCity(String city, int days) {
        String c = city.trim().toUpperCase();
        int span = Math.min(Math.max(days, 1), MAX_DAYS);
        LocalDate today = LocalDate.now(PickupSlots.ZONE);
        Instant from = PickupSlots.resolve(today, PickupSlots.startHours().get(0)).start();
        Instant to = today.plusDays(span).atStartOfDay(PickupSlots.ZONE).toInstant();

        // One grouped query for the whole window, then look each slot up by its start instant.
        Map<Instant, Long> booked = new HashMap<>();
        for (SlotBookingCount row : shipments.countBookedSlotsInRange(c, PickupType.DA_PICKUP, from, to)) {
            booked.put(row.getStart(), row.getCount());
        }

        int max = props.getMaxPerSlot();
        List<Slot> slots = new ArrayList<>();
        for (int d = 0; d < span; d++) {
            LocalDate date = today.plusDays(d);
            for (int hour : PickupSlots.startHours()) {
                Instant start = PickupSlots.resolve(date, hour).start();
                long count = booked.getOrDefault(start, 0L);
                int remaining = (int) Math.max(0, max - count);
                slots.add(new Slot(date, hour, remaining, remaining == 0));
            }
        }
        return new SlotAvailabilityResponse(c, slots);
    }
}
