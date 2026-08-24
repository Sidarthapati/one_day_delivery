package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.ScheduledPickup;
import com.oneday.dispatch.repository.ScheduledPickupRepository;
import com.oneday.dispatch.service.DispatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledPickupServiceImplTest {

    private ScheduledPickupRepository repo;
    private DispatchService dispatchService;
    private ScheduledPickupServiceImpl service;

    private final UUID shipment = UUID.randomUUID();
    private final UUID city = UUID.randomUUID();
    private final UUID tile = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final String orderRef = "1DD-ORD-BLR-20260824-00001";

    @BeforeEach
    void setUp() {
        repo = mock(ScheduledPickupRepository.class);
        dispatchService = mock(DispatchService.class);
        service = new ScheduledPickupServiceImpl(repo, dispatchService, new DispatchProperties());
    }

    @Test
    void futureSlotIsHeldWithReleaseSixtyMinBefore() {
        Instant slotStart = Instant.now().plus(3, ChronoUnit.HOURS);
        Instant slotEnd = slotStart.plus(2, ChronoUnit.HOURS);

        boolean held = service.holdIfNotDue(shipment, city, tile, 12.9, 77.6, "PREPAID", slotStart, slotEnd,
                orderId, orderRef);

        assertThat(held).isTrue();
        ArgumentCaptor<ScheduledPickup> cap = ArgumentCaptor.forClass(ScheduledPickup.class);
        verify(repo).save(cap.capture());
        // Default lead is 60 min.
        assertThat(cap.getValue().getReleaseAt())
                .isCloseTo(slotStart.minus(60, ChronoUnit.MINUTES), within(1, ChronoUnit.SECONDS));
        assertThat(cap.getValue().getStatus()).isEqualTo("HELD");
        // The parent order is stored on the hold so the released bulk pickup still groups with its siblings.
        assertThat(cap.getValue().getOrderId()).isEqualTo(orderId);
        assertThat(cap.getValue().getOrderRef()).isEqualTo(orderRef);
    }

    @Test
    void alreadyDueSlotIsNotHeld() {
        Instant slotStart = Instant.now().minus(1, ChronoUnit.HOURS);   // release_at is in the past

        boolean held = service.holdIfNotDue(shipment, city, tile, 12.9, 77.6, "COD", slotStart, null,
                orderId, orderRef);

        assertThat(held).isFalse();
        verify(repo, never()).save(any());
    }

    @Test
    void releaseDueAssignsAndMarksReleased() {
        ScheduledPickup sp = new ScheduledPickup();
        sp.setShipmentId(shipment);
        sp.setCityId(city);
        sp.setTileId(tile);
        sp.setPickupLat(12.9);
        sp.setPickupLon(77.6);
        sp.setPaymentMode("PREPAID");
        sp.setOrderId(orderId);
        sp.setOrderRef(orderRef);
        sp.setStatus("HELD");
        when(repo.findByStatusAndReleaseAtLessThanEqual(eq("HELD"), any())).thenReturn(List.of(sp));

        int n = service.releaseDue();

        assertThat(n).isEqualTo(1);
        // The release carries the held order through so the assigned pickup groups with its siblings.
        verify(dispatchService).assignPickup(eq(shipment), eq(city), eq(12.9), eq(77.6), eq(tile), eq("PREPAID"),
                eq(orderId), eq(orderRef));
        assertThat(sp.getStatus()).isEqualTo("RELEASED");
        assertThat(sp.getReleasedAt()).isNotNull();
    }
}
