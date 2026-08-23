package com.oneday.orders.events;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.kafka.enums.ScanEventType;
import com.oneday.common.kafka.events.ScanEvent;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.ShipmentStateMachine;
import com.oneday.orders.service.exception.IllegalStateTransitionException;
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
    void anyScan_stampsLastScanAtomically() {
        UUID id = UUID.randomUUID();
        Instant scannedAt = Instant.parse("2026-08-23T10:00:00Z");

        consumer.onScanEvent(new ScanEvent(id, ScanEventType.HUB_ORIGIN_IN, null, scannedAt));

        // Newer-only guard lives in the UPDATE's WHERE clause (no read-check-write race); the consumer
        // just fires the atomic stamp with the scan's time + type.
        verify(shipmentRepository).updateLastScanIfNewer(id, scannedAt, "HUB_ORIGIN_IN");
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

    @Test
    void duplicateScan_alreadyInTargetState_isSwallowed() {
        // A re-scan: the parcel is already AT_DEST_HUB. The transition is illegal (from == to) but
        // idempotent — ack, don't propagate to retry/DLQ.
        UUID id = UUID.randomUUID();
        doThrow(new IllegalStateTransitionException(ShipmentState.AT_DEST_HUB, ShipmentState.AT_DEST_HUB))
                .when(stateMachine).transition(eq(id), eq(ShipmentState.AT_DEST_HUB), any());

        consumer.onScanEvent(new ScanEvent(id, ScanEventType.HUB_DEST_IN)); // no throw
    }

    @Test
    void outOfOrderScan_predecessorNotYetApplied_rethrowsForRetry() {
        // Origin-out arrived before the bag-created event set IN_TAKEOFF_BAG. from != target → rethrow
        // so the listener retry re-delivers once the predecessor lands.
        UUID id = UUID.randomUUID();
        doThrow(new IllegalStateTransitionException(ShipmentState.ORIGIN_HUB_PROCESSING, ShipmentState.DISPATCHED_TO_AIRPORT))
                .when(stateMachine).transition(eq(id), eq(ShipmentState.DISPATCHED_TO_AIRPORT), any());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        consumer.onScanEvent(new ScanEvent(id, ScanEventType.HUB_ORIGIN_OUT)))
                .isInstanceOf(IllegalStateTransitionException.class);
    }
}
