package com.oneday.barcode.events;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.oneday.common.kafka.enums.ScanEventType;
import com.oneday.common.kafka.events.ScanEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mirrors the app's Rabbit ObjectMapper (SNAKE_CASE + JavaTime) to prove ScanEvent round-trips —
 * i.e. the consumer can deserialize exactly what M8 publishes. Guards against a creator-ambiguity
 * regression from the record's extra constructor.
 */
class ScanEventJacksonTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Test
    void roundTrips_labelEvent() throws Exception {
        ScanEvent original = new ScanEvent(UUID.randomUUID(), ScanEventType.LABEL_GENERATED,
                "1DD-BOM-260711-000001", Instant.parse("2026-07-11T16:35:59.449781Z"));

        String wire = mapper.writeValueAsString(original);
        ScanEvent back = mapper.readValue(wire, ScanEvent.class);

        assertThat(back.eventType()).isEqualTo(ScanEventType.LABEL_GENERATED);
        assertThat(back.parcelId()).isEqualTo("1DD-BOM-260711-000001");
        assertThat(back.occurredAt()).isEqualTo(original.occurredAt());
    }
}
