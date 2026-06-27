package com.oneday.hub.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.oneday.common.kafka.enums.HubEventType;
import com.oneday.common.kafka.events.HubEvent;
import com.oneday.common.kafka.events.hub.BagCreatedEvent;
import com.oneday.common.kafka.events.hub.StandAssignedEvent;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the typed hub payloads round-trip through Jackson and — crucially — that the tolerant
 * {@link HubEvent} reader can recover {@code shipmentId} + {@code eventType} from a typed payload's
 * JSON body. If {@code eventType} were not on the wire, M4's consumer could not discriminate.
 */
class HubEventPayloadJacksonTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void standAssigned_roundTrips() throws Exception {
        StandAssignedEvent e = new StandAssignedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "A-2", "MUMBAI", "OUTBOUND");

        String json = mapper.writeValueAsString(e);
        StandAssignedEvent back = mapper.readValue(json, StandAssignedEvent.class);

        assertThat(back).isEqualTo(e);
        assertThat(json).contains("\"eventType\":\"STAND_ASSIGNED\"");
    }

    @Test
    void tolerantHubEventReader_recoversTypeAndShipmentId() throws Exception {
        UUID shipmentId = UUID.randomUUID();
        StandAssignedEvent e = new StandAssignedEvent(
                shipmentId, UUID.randomUUID(), UUID.randomUUID(), "A-2", "MUMBAI", "OUTBOUND");

        String json = mapper.writeValueAsString(e);
        HubEvent tolerant = mapper.readValue(json, HubEvent.class);

        assertThat(tolerant.shipmentId()).isEqualTo(shipmentId);
        assertThat(tolerant.eventType()).isEqualTo(HubEventType.STAND_ASSIGNED);
    }

    @Test
    void bagCreated_roundTrips() throws Exception {
        BagCreatedEvent e = new BagCreatedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "ODMUMBAI18", LocalDate.of(2026, 6, 27), "MUMBAI", "A-2");

        String json = mapper.writeValueAsString(e);
        assertThat(mapper.readValue(json, BagCreatedEvent.class)).isEqualTo(e);
        assertThat(json).contains("\"eventType\":\"BAG_CREATED\"");
    }
}
