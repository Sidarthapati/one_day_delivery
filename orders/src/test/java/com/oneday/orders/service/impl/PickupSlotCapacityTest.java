package com.oneday.orders.service.impl;

import com.oneday.orders.config.PickupSlotProperties;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.BookingService;
import com.oneday.orders.service.PickupSlotFullException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The per-slot cap is the whole point of the feature: a full slot must be rejected, and it must reject
 * BEFORE payment (this component runs pre-payment). These pin the four branches so a regression that
 * dropped the cap, or the ASAP short-circuit, or the pre-charge validation, fails loudly.
 */
class PickupSlotCapacityTest {

    private final ShipmentRepository repo = mock(ShipmentRepository.class);
    private final PickupSlotProperties props = mock(PickupSlotProperties.class);
    private final PickupSlotCapacity capacity = new PickupSlotCapacity(repo, props);

    private static final LocalDate DATE = LocalDate.of(2026, 8, 27);
    private static final int VALID_HOUR = 9;

    @Test
    void asapBookingSkipsTheCapEntirely() {
        assertThatCode(() -> capacity.ensureRoom("DEL", null, null)).doesNotThrowAnyException();
        verify(repo, never()).countByOriginCityAndScheduledPickupStartAndCancelledAtIsNull(any(), any());
    }

    @Test
    void allowsWhenSlotHasRoom() {
        when(props.getMaxPerSlot()).thenReturn(2);
        when(repo.countByOriginCityAndScheduledPickupStartAndCancelledAtIsNull(eq("DEL"), any(Instant.class)))
                .thenReturn(1);
        assertThatCode(() -> capacity.ensureRoom("del", DATE, VALID_HOUR)).doesNotThrowAnyException();
    }

    @Test
    void rejectsWhenSlotIsFull() {
        when(props.getMaxPerSlot()).thenReturn(2);
        when(repo.countByOriginCityAndScheduledPickupStartAndCancelledAtIsNull(eq("DEL"), any(Instant.class)))
                .thenReturn(2);
        assertThatThrownBy(() -> capacity.ensureRoom("DEL", DATE, VALID_HOUR))
                .isInstanceOf(PickupSlotFullException.class);
    }

    @Test
    void rejectsAnInvalidStartHourBeforeAnyCapLookup() {
        assertThatThrownBy(() -> capacity.ensureRoom("DEL", DATE, 8))   // 8 is not a valid slot start
                .isInstanceOf(BookingService.InvalidBookingRequestException.class);
        verify(repo, never()).countByOriginCityAndScheduledPickupStartAndCancelledAtIsNull(any(), any());
    }
}
