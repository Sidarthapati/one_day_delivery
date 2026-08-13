package com.oneday.common.kafka;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.oneday.common.kafka.enums.HubEventType;
import com.oneday.common.kafka.events.HubEvent;
import com.oneday.common.kafka.events.hub.StandAssignedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The M7→M4 seam regression: M7 publishes concrete event classes (their own __TypeId__), but the
 * orders.hub consumer binds the umbrella {@link HubEvent}. A header-driven class mapper deserialized to
 * the concrete class and failed "Cannot convert from [StandAssignedEvent] to [HubEvent]", dead-lettering
 * every hub event. INFERRED type resolution must deserialize to the listener's declared type instead.
 */
class MessagingConfigTest {

    private final MessageConverter converter = new MessagingConfig().jsonMessageConverter(mapper());

    // Mirrors the app's Boot ObjectMapper: snake_case + tolerant of unknown fields.
    private static ObjectMapper mapper() {
        return new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Test
    void concreteHubEventDeserializesToUmbrellaHubEvent() throws Exception {
        UUID shipmentId = UUID.randomUUID();
        StandAssignedEvent concrete =
                new StandAssignedEvent(shipmentId, UUID.randomUUID(), UUID.randomUUID(), "A-3", "DELHI", "OUTBOUND");

        Message msg = converter.toMessage(concrete, new MessageProperties());
        // The producer stamps the concrete class — this is exactly what tripped the old class mapper.
        assertThat(msg.getMessageProperties().getHeader("__TypeId__").toString()).contains("StandAssignedEvent");

        // The listener container sets the method's declared parameter type here before converting;
        // with INFERRED precedence that type wins over the __TypeId__ header.
        msg.getMessageProperties().setInferredArgumentType(HubEvent.class);
        Object out = converter.fromMessage(msg);

        assertThat(out).isInstanceOf(HubEvent.class);
        HubEvent hub = (HubEvent) out;
        assertThat(hub.eventType()).isEqualTo(HubEventType.STAND_ASSIGNED);
        assertThat(hub.shipmentId()).isEqualTo(shipmentId);
    }

    /**
     * DEST_SORT_COMPLETE ({@code DestSortCompleteEvent}) keys its parcel by {@code parcel_id}, not
     * {@code shipment_id}. Without the alias on {@link HubEvent#shipmentId()} it bound to null, so the M4
     * consumer's {@code shipmentId==null} guard silently dropped every dest-sort event and parcels froze
     * at {@code AT_DEST_HUB}. This is the wire shape that event produces (java.time fields elided — the
     * umbrella ignores them); the id must bind so the last mile can start.
     */
    @Test
    void hubEventBindsParcelIdAliasToShipmentId() throws Exception {
        UUID shipmentId = UUID.randomUUID();
        String wire = "{\"parcel_id\":\"" + shipmentId + "\",\"eventType\":\"DEST_SORT_COMPLETE\","
                + "\"city_id\":\"" + UUID.randomUUID() + "\",\"hub_id\":\"" + UUID.randomUUID() + "\"}";

        HubEvent hub = mapper().readValue(wire, HubEvent.class);

        assertThat(hub.shipmentId()).isEqualTo(shipmentId);   // was null before the @JsonAlias fix
        assertThat(hub.eventType()).isEqualTo(HubEventType.DEST_SORT_COMPLETE);
    }
}
