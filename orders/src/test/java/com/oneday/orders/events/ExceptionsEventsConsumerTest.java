package com.oneday.orders.events;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.kafka.enums.ExceptionsEventType;
import com.oneday.common.kafka.events.ExceptionsEvent;
import com.oneday.orders.service.ShipmentStateMachine;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** M11 exception events → M4 state transitions. Guards the reschedule-vs-reassign distinction. */
class ExceptionsEventsConsumerTest {

    private final ShipmentStateMachine sm = mock(ShipmentStateMachine.class);
    private final ExceptionsEventsConsumer consumer = new ExceptionsEventsConsumer(sm);

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
}
