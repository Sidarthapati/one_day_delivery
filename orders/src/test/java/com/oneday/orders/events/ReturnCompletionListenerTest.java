package com.oneday.orders.events;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.ShipmentStateMachine;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A delivered return child closes its original as RTO_COMPLETED. */
class ReturnCompletionListenerTest {

    private final ShipmentRepository shipmentRepo = mock(ShipmentRepository.class);
    private final ShipmentStateMachine sm = mock(ShipmentStateMachine.class);
    private final ReturnCompletionListener listener = new ReturnCompletionListener(shipmentRepo, sm);

    private ShipmentTransitioned droppedEvent(UUID shipmentId, String ref) {
        return new ShipmentTransitioned(shipmentId, ref, ShipmentState.DROP_COLLECTED,
                ShipmentState.DROPPED, "t", null, null);
    }

    @Test
    void deliveredReturnChildCompletesTheOriginal() {
        UUID childId = UUID.randomUUID();
        UUID originalId = UUID.randomUUID();
        Shipment child = new Shipment();
        ReflectionTestUtils.setField(child, "id", childId);
        child.setShipmentRef("1DD-DEL-20260828-00001_R");
        child.setReturnOfShipmentId(originalId);
        when(shipmentRepo.findById(childId)).thenReturn(Optional.of(child));

        listener.onShipmentTransitioned(droppedEvent(childId, child.getShipmentRef()));

        verify(sm).transition(eq(originalId), eq(ShipmentState.RTO_COMPLETED), any());
    }

    @Test
    void deliveredNonReturnShipmentDoesNothing() {
        UUID id = UUID.randomUUID();
        Shipment plain = new Shipment(); // returnOfShipmentId == null
        when(shipmentRepo.findById(id)).thenReturn(Optional.of(plain));

        listener.onShipmentTransitioned(droppedEvent(id, "1DD-DEL-20260828-00002"));

        verify(sm, never()).transition(any(), any(), any());
    }

    @Test
    void nonDeliveredTransitionIsIgnored() {
        UUID id = UUID.randomUUID();
        listener.onShipmentTransitioned(new ShipmentTransitioned(id, "ref", ShipmentState.AT_DEST_HUB,
                ShipmentState.DEST_HUB_PROCESSING, "t", null, null));
        verify(sm, never()).transition(any(), any(), any());
    }
}
