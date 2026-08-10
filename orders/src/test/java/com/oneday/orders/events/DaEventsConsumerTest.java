package com.oneday.orders.events;

import com.oneday.common.domain.MeetingMode;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.kafka.enums.DaEventType;
import com.oneday.common.kafka.events.DaLifecycleEvent;
import com.oneday.common.port.CityMeetingModePort;
import com.oneday.orders.service.DeliveryOtpService;
import com.oneday.orders.service.PickupOtpService;
import com.oneday.orders.service.ShipmentStateMachine;
import com.oneday.orders.service.exception.IllegalStateTransitionException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.*;

/**
 * The idempotency guard that keeps M5's event-driven assignment (PICKUP_ASSIGNED / DROP_ASSIGNED) from
 * dead-lettering when the demo has already fast-forwarded the shipment past the target state, plus the
 * pickup/delivery OTP side-effects minted on real transitions.
 */
class DaEventsConsumerTest {

    private final ShipmentStateMachine stateMachine = mock(ShipmentStateMachine.class);
    private final PickupOtpService pickupOtp = mock(PickupOtpService.class);
    private final DeliveryOtpService deliveryOtp = mock(DeliveryOtpService.class);
    private final CityMeetingModePort meetingMode = mock(CityMeetingModePort.class);
    private final DaEventsConsumer consumer =
            new DaEventsConsumer(stateMachine, pickupOtp, deliveryOtp, meetingMode);

    private DaLifecycleEvent event(DaEventType type, UUID shipmentId, UUID cityId) {
        return new DaLifecycleEvent(UUID.randomUUID(), type, "1.0", Instant.now(),
                shipmentId, "REF", UUID.randomUUID(), cityId, null, null, null, null, null);
    }

    private DaLifecycleEvent pickupAssigned(UUID shipmentId) {
        return event(DaEventType.PICKUP_ASSIGNED, shipmentId, UUID.randomUUID());
    }

    @Test
    void assignedEvent_transitionsAndMintsPickupOtp() {
        UUID id = UUID.randomUUID();
        consumer.onDaEvent(pickupAssigned(id));
        verify(stateMachine).transition(eq(id), eq(ShipmentState.PICKUP_ASSIGNED), any());
        verify(pickupOtp).generate(id);         // pickup OTP minted only on a real transition
        verify(deliveryOtp, never()).generate(any());
    }

    @Test
    void dropCollectedVanCity_transitionsAndMintsDeliveryOtp() {
        UUID id = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        when(meetingMode.modeFor(cityId)).thenReturn(MeetingMode.VAN_MEETING);
        consumer.onDaEvent(event(DaEventType.DROP_COLLECTED, id, cityId));
        verify(stateMachine).transition(eq(id), eq(ShipmentState.DROP_COLLECTED), any());
        verify(deliveryOtp).generate(id);       // out for delivery → recipient OTP minted
        verify(pickupOtp, never()).generate(any());
    }

    @Test
    void collectedFromHubReturnCity_transitionsAndMintsDeliveryOtp() {
        UUID id = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        when(meetingMode.modeFor(cityId)).thenReturn(MeetingMode.HUB_RETURN);
        consumer.onDaEvent(event(DaEventType.DROP_COLLECTED, id, cityId));
        verify(stateMachine).transition(eq(id), eq(ShipmentState.COLLECTED_FROM_HUB), any());
        verify(deliveryOtp).generate(id);       // HUB_RETURN out for delivery → recipient OTP minted
        verify(pickupOtp, never()).generate(any());
    }

    @Test
    void alreadyAdvanced_isSkippedNotDeadLettered_andNoOtp() {
        UUID id = UUID.randomUUID();
        doThrow(new IllegalStateTransitionException(ShipmentState.PICKED_UP, ShipmentState.PICKUP_ASSIGNED))
                .when(stateMachine).transition(eq(id), eq(ShipmentState.PICKUP_ASSIGNED), any());
        // must NOT rethrow (rethrow → RabbitMQ retry → DLQ), and must NOT double-mint the OTP
        consumer.onDaEvent(pickupAssigned(id));
        verify(pickupOtp, never()).generate(any());
        verify(deliveryOtp, never()).generate(any());
    }

    @Test
    void deletedShipment_isSkippedNotDeadLettered() {
        UUID id = UUID.randomUUID();
        // demo "Clear bookings" deleted the shipment out from under an in-flight event
        doThrow(new jakarta.persistence.EntityNotFoundException("Shipment not found: " + id))
                .when(stateMachine).transition(eq(id), eq(ShipmentState.PICKUP_ASSIGNED), any());
        consumer.onDaEvent(pickupAssigned(id));   // must not throw → no retry/DLQ
        verify(pickupOtp, never()).generate(any());
        verify(deliveryOtp, never()).generate(any());
    }
}
