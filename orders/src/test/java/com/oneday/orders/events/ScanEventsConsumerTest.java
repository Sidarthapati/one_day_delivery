package com.oneday.orders.events;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.kafka.enums.ScanEventType;
import com.oneday.common.kafka.events.ScanEvent;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.ShipmentStateMachine;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;

/**
 * The M8 seam: LABEL_GENERATED stamps the barcode on the shipment (no state transition); every other
 * scan type drives the state machine and never touches parcel_id.
 */
class ScanEventsConsumerTest {

    private final ShipmentStateMachine stateMachine = mock(ShipmentStateMachine.class);
    private final ShipmentRepository shipmentRepository = mock(ShipmentRepository.class);
    private final ScanEventsConsumer consumer = new ScanEventsConsumer(stateMachine, shipmentRepository);

    @Test
    void labelGenerated_stampsParcelId_withNoTransition() {
        UUID id = UUID.randomUUID();
        Shipment shipment = mock(Shipment.class);
        when(shipmentRepository.findById(id)).thenReturn(Optional.of(shipment));

        consumer.onScanEvent(new ScanEvent(id, ScanEventType.LABEL_GENERATED, "1DD-DEL-260711-000042", Instant.now()));

        verify(shipment).setParcelId("1DD-DEL-260711-000042");
        verify(shipmentRepository).save(shipment);
        verifyNoInteractions(stateMachine);
    }

    @Test
    void hubOriginIn_transitions_andNeverStampsParcelId() {
        UUID id = UUID.randomUUID();

        consumer.onScanEvent(new ScanEvent(id, ScanEventType.HUB_ORIGIN_IN));

        verify(stateMachine).transition(eq(id), eq(ShipmentState.AT_ORIGIN_HUB), any());
        verify(shipmentRepository, never()).save(any());
    }

    @Test
    void hubOriginOut_transitionsToDispatchedToAirport() {
        UUID id = UUID.randomUUID();

        consumer.onScanEvent(new ScanEvent(id, ScanEventType.HUB_ORIGIN_OUT));

        verify(stateMachine).transition(eq(id), eq(ShipmentState.DISPATCHED_TO_AIRPORT), any());
    }

    @Test
    void destShuttleIn_transitionsToDispatchedToHub() {
        UUID id = UUID.randomUUID();

        consumer.onScanEvent(new ScanEvent(id, ScanEventType.DEST_SHUTTLE_IN));

        verify(stateMachine).transition(eq(id), eq(ShipmentState.DISPATCHED_TO_HUB), any());
    }

    @Test
    void delivered_isCustodyFactOnly_noTransition() {
        // Option A: DELIVERED is recorded in M8 but DROPPED stays owned by the delivery-OTP path.
        consumer.onScanEvent(new ScanEvent(UUID.randomUUID(), ScanEventType.DELIVERED));

        verifyNoInteractions(stateMachine);
        verify(shipmentRepository, never()).save(any());
    }

    @Test
    void labelGenerated_withNullParcelId_isIgnored() {
        UUID id = UUID.randomUUID();

        consumer.onScanEvent(new ScanEvent(id, ScanEventType.LABEL_GENERATED)); // parcelId null

        verifyNoInteractions(stateMachine);
        verify(shipmentRepository, never()).save(any());
    }
}
