package com.oneday.orders.service.impl;

import com.oneday.common.domain.PickupSlots;
import com.oneday.orders.config.PickupSlotProperties;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.BookingService;
import com.oneday.orders.service.PickupSlotFullException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Enforces the per-slot pickup cap at booking time, shared by the B2C and B2B booking flows.
 * Called <em>before</em> payment, so a full (or invalid) slot rejects without ever charging.
 */
@Component
class PickupSlotCapacity {

    private final ShipmentRepository shipmentRepository;
    private final PickupSlotProperties props;

    PickupSlotCapacity(ShipmentRepository shipmentRepository, PickupSlotProperties props) {
        this.shipmentRepository = shipmentRepository;
        this.props = props;
    }

    /**
     * No-op for an ASAP booking (no slot chosen). Otherwise validate the slot and reject with
     * {@link PickupSlotFullException} if the (origin city, slot) already holds {@code maxPerSlot} active
     * reservations.
     *
     * <p>ponytail: soft cap — the count-then-book gap lets concurrent writers overbook a slot by up to
     * (concurrent bookings − 1). A hard cap needs a per-slot counter row + row lock (mirror
     * {@code VanManifestServiceImpl}); add it only if overbooking actually bites at pilot volume.
     */
    void ensureRoom(String originCity, LocalDate slotDate, Integer startHour) {
        if (slotDate == null && startHour == null) {
            return;   // ASAP — no slot to cap
        }
        // Same rule as applyScheduledPickup, but pre-payment so a bad slot never charges the customer.
        if (slotDate == null || startHour == null || !PickupSlots.isValidStartHour(startHour)) {
            throw new BookingService.InvalidBookingRequestException(
                    "pickupSlotDate and a valid pickupSlotStartHour (7/9/11/13/15/17/19) are both required");
        }
        String city = originCity.toUpperCase();
        Instant slotStart = PickupSlots.resolve(slotDate, startHour).start();
        int booked = shipmentRepository
                .countByOriginCityAndScheduledPickupStartAndCancelledAtIsNull(city, slotStart);
        if (booked >= props.getMaxPerSlot()) {
            throw new PickupSlotFullException(
                    "The " + startHour + ":00 pickup slot for " + city + " is full. Please choose another slot.");
        }
    }
}
