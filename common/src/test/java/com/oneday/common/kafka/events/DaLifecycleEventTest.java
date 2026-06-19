package com.oneday.common.kafka.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.common.kafka.enums.DaEventType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PR #1 contract test: M5's rich {@link DaLifecycleEvent} must remain readable by M4's minimal
 * {@link DaEvent} reader (the producer/consumer contract on {@code oneday.da.events}), and its
 * {@link DaLifecycleEvent#eventTypeName()} routing key / {@link DaLifecycleEvent#partitionKey()}
 * must behave as the bus expects.
 */
class DaLifecycleEventTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void eventTypeNameIsTheEnumName() {
        DaLifecycleEvent e = base(DaEventType.PICKUP_ASSIGNED, UUID.randomUUID(), UUID.randomUUID());
        assertEquals("PICKUP_ASSIGNED", e.eventTypeName());
    }

    @Test
    void partitionKeyIsShipmentThenFallsBackToDa() {
        UUID da = UUID.randomUUID();

        DaLifecycleEvent shipmentScoped = base(DaEventType.PICKUP_ASSIGNED, UUID.randomUUID(), da);
        assertEquals(shipmentScoped.shipmentId().toString(), shipmentScoped.partitionKey());

        DaLifecycleEvent daScoped = base(DaEventType.DA_ABSENT, null, da);
        assertEquals(da.toString(), daScoped.partitionKey());
    }

    @Test
    void m5RichPayloadIsReadableByM4MinimalDaEvent() throws Exception {
        UUID shipmentId = UUID.randomUUID();
        DaLifecycleEvent produced = base(DaEventType.PICKUP_FAILED, shipmentId, UUID.randomUUID());

        // M5 serialises the rich record; M4 reads it back through the minimal DaEvent contract.
        String json = mapper.writeValueAsString(produced);
        DaEvent consumed = mapper.readValue(json, DaEvent.class);

        assertEquals(shipmentId, consumed.shipmentId());
        assertEquals(DaEventType.PICKUP_FAILED, consumed.eventType());
    }

    // occurredAt left null so this pure-unit test needs no jackson-jsr310 module on the test classpath.
    private DaLifecycleEvent base(DaEventType type, UUID shipmentId, UUID daId) {
        return new DaLifecycleEvent(
                UUID.randomUUID(), type, DaLifecycleEvent.SCHEMA_VERSION, null,
                shipmentId, "1DD-REF", daId, UUID.randomUUID(),
                12.97, 77.61,
                type == DaEventType.PICKUP_FAILED ? "OTP_EXPIRED" : null);
    }
}
