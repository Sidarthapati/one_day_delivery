package com.oneday.orders.events;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.kafka.enums.DaEventType;
import com.oneday.common.kafka.events.DaLifecycleEvent;
import com.oneday.orders.service.PickupOtpService;
import com.oneday.orders.service.ShipmentStateMachine;
import com.oneday.orders.service.exception.IllegalStateTransitionException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.*;

/**
 * The idempotency guard that keeps M5's event-driven assignment (PICKUP_ASSIGNED / DROP_ASSIGNED) from
 * dead-lettering when the demo has already fast-forwarded the shipment past the target state.
 */
class DaEventsConsumerTest {

    private final ShipmentStateMachine stateMachine = mock(ShipmentStateMachine.class);
    private final PickupOtpService otp = mock(PickupOtpService.class);
    private final DaEventsConsumer consumer = new DaEventsConsumer(stateMachine, otp);

    private DaLifecycleEvent pickupAssigned(UUID shipmentId) {
        return new DaLifecycleEvent(UUID.randomUUID(), DaEventType.PICKUP_ASSIGNED, "1.0", Instant.now(),
                shipmentId, "REF", UUID.randomUUID(), UUID.randomUUID(), null, null, null, null, null);
    }

    @Test
    void assignedEvent_transitionsAndMintsOtp() {
        UUID id = UUID.randomUUID();
        consumer.onDaEvent(pickupAssigned(id));
        verify(stateMachine).transition(eq(id), eq(ShipmentState.PICKUP_ASSIGNED), any());
        verify(otp).generate(id);   // OTP minted only on a real transition
    }

    @Test
    void alreadyAdvanced_isSkippedNotDeadLettered_andNoOtp() {
        UUID id = UUID.randomUUID();
        doThrow(new IllegalStateTransitionException(ShipmentState.PICKED_UP, ShipmentState.PICKUP_ASSIGNED))
                .when(stateMachine).transition(eq(id), eq(ShipmentState.PICKUP_ASSIGNED), any());
        // must NOT rethrow (rethrow → RabbitMQ retry → DLQ), and must NOT double-mint the OTP
        consumer.onDaEvent(pickupAssigned(id));
        verify(otp, never()).generate(any());
    }

    @Test
    void deletedShipment_isSkippedNotDeadLettered() {
        UUID id = UUID.randomUUID();
        // demo "Clear bookings" deleted the shipment out from under an in-flight event
        doThrow(new jakarta.persistence.EntityNotFoundException("Shipment not found: " + id))
                .when(stateMachine).transition(eq(id), eq(ShipmentState.PICKUP_ASSIGNED), any());
        consumer.onDaEvent(pickupAssigned(id));   // must not throw → no retry/DLQ
        verify(otp, never()).generate(any());
    }
}
