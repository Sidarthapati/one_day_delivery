package com.oneday.orders.events;

import com.oneday.common.domain.enums.ReturnReason;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.kafka.enums.ExceptionsEventType;
import com.oneday.common.kafka.events.ExceptionsEvent;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.ReturnService;
import com.oneday.orders.service.ShipmentStateMachine;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** M11 exception events → M4 state transitions + the return-spawn rewire. */
class ExceptionsEventsConsumerTest {

    private final ShipmentStateMachine sm = mock(ShipmentStateMachine.class);
    private final ShipmentRepository shipmentRepository = mock(ShipmentRepository.class);
    private final ReturnService returnService = mock(ReturnService.class);
    private final ExceptionsEventsConsumer consumer =
            new ExceptionsEventsConsumer(sm, shipmentRepository, returnService);

    @Test
    void reassignReDrivesHandedToDropVan() {
        UUID id = UUID.randomUUID();
        consumer.onExceptionsEvent(new ExceptionsEvent(id, ExceptionsEventType.DELIVERY_REASSIGNED));
        // HANDED_TO_DROP_VAN is M5's assignment trigger → dispatch scores a fresh DA.
        verify(sm).transition(eq(id), eq(ShipmentState.HANDED_TO_DROP_VAN), any());
    }

    @Test
    void rescheduleOnlyFlipsToDropAssigned() {
        UUID id = UUID.randomUUID();
        consumer.onExceptionsEvent(new ExceptionsEvent(id, ExceptionsEventType.DELIVERY_RESCHEDULED));
        verify(sm).transition(eq(id), eq(ShipmentState.DROP_ASSIGNED), any());
    }

    @Test
    void rtoInitiatedSpawnsAReturnChildForAnOriginal() {
        UUID id = UUID.randomUUID();
        Shipment original = new Shipment();       // returnOfShipmentId == null → an original, not a child
        when(shipmentRepository.findById(id)).thenReturn(Optional.of(original));

        consumer.onExceptionsEvent(new ExceptionsEvent(id, ExceptionsEventType.RTO_INITIATED));

        verify(returnService).initiateReturn(eq(id), eq(ReturnReason.ATTEMPTS_EXHAUSTED), any());
        verify(sm, never()).transition(any(), eq(ShipmentState.RTO_INITIATED), any());
    }

    @Test
    void rtoInitiatedOnAReturnChildHoldsItAtTheHub() {
        UUID id = UUID.randomUUID();
        Shipment child = new Shipment();
        child.setReturnOfShipmentId(UUID.randomUUID()); // this shipment is itself a return child
        when(shipmentRepository.findById(id)).thenReturn(Optional.of(child));

        consumer.onExceptionsEvent(new ExceptionsEvent(id, ExceptionsEventType.RTO_INITIATED));

        verify(sm).transition(eq(id), eq(ShipmentState.HELD_AT_HUB), any());
        verify(returnService, never()).initiateReturn(any(), any(), any());
    }
}
