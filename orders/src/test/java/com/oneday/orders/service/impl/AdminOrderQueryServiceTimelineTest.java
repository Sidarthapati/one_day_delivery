package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.port.ShipmentScanTrailPort;
import com.oneday.common.port.ShipmentScanTrailPort.ScanTrailEntry;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.domain.ShipmentStateHistory;
import com.oneday.orders.dto.ShipmentTimelineResponse;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.repository.ShipmentStateHistoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminOrderQueryServiceTimelineTest {

    private final ShipmentRepository shipmentRepo = mock(ShipmentRepository.class);
    private final com.oneday.orders.repository.ParcelOrderRepository parcelOrderRepo =
            mock(com.oneday.orders.repository.ParcelOrderRepository.class);
    private final ShipmentStateHistoryRepository historyRepo = mock(ShipmentStateHistoryRepository.class);
    private final ShipmentScanTrailPort scanTrail = mock(ShipmentScanTrailPort.class);
    private final AdminOrderQueryServiceImpl svc =
            new AdminOrderQueryServiceImpl(shipmentRepo, parcelOrderRepo, historyRepo, scanTrail);

    private Shipment shipment(UUID id, String ref) {
        Shipment s = mock(Shipment.class);
        when(s.getId()).thenReturn(id);
        when(s.getShipmentRef()).thenReturn(ref);
        when(s.getState()).thenReturn(ShipmentState.PICKED_UP);
        when(s.getOriginCity()).thenReturn("BLR");
        when(s.getDestCity()).thenReturn("DEL");
        return s;
    }

    private ShipmentStateHistory hist(ShipmentState to, Instant at) {
        ShipmentStateHistory h = mock(ShipmentStateHistory.class);
        when(h.getToState()).thenReturn(to);
        when(h.getOccurredAt()).thenReturn(at);
        return h;
    }

    @Test
    void mergesStateHistoryAndScansInTimeOrder() {
        UUID id = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-08-24T09:00:00Z");
        Shipment s = shipment(id, "1DD-1");
        ShipmentStateHistory booked = hist(ShipmentState.BOOKED, t0);
        ShipmentStateHistory pickedUp = hist(ShipmentState.PICKED_UP, t0.plusSeconds(1200));
        when(shipmentRepo.findByShipmentRef("1DD-1")).thenReturn(Optional.of(s));
        when(historyRepo.findByShipmentIdOrderByOccurredAtAsc(id)).thenReturn(List.of(booked, pickedUp));
        when(scanTrail.trailFor(id)).thenReturn(List.of(
                new ScanTrailEntry("PICKUP_SCAN", "DA", UUID.randomUUID(), UUID.randomUUID(), t0.plusSeconds(600))));

        ShipmentTimelineResponse r = svc.timeline("1DD-1", null);

        // interleaved by time: BOOKED (state) → PICKUP_SCAN (scan) → PICKED_UP (state)
        assertThat(r.events()).extracting(ShipmentTimelineResponse.TimelineEvent::label)
                .containsExactly("BOOKED", "PICKUP_SCAN", "PICKED_UP");
        assertThat(r.events()).extracting(ShipmentTimelineResponse.TimelineEvent::kind)
                .containsExactly("STATE", "SCAN", "STATE");
        assertThat(r.shipmentRef()).isEqualTo("1DD-1");
    }

    @Test
    void unknownRef404s() {
        when(shipmentRepo.findByShipmentRef("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.timeline("nope", null)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void shipmentOutsideManagerCity404s() {
        UUID id = UUID.randomUUID();
        Shipment s = shipment(id, "1DD-1");
        when(shipmentRepo.findByShipmentRef("1DD-1")).thenReturn(Optional.of(s));
        // BLR↔DEL shipment; a HYD manager can't see it.
        assertThatThrownBy(() -> svc.timeline("1DD-1", "HYD")).isInstanceOf(ResponseStatusException.class);
    }
}
