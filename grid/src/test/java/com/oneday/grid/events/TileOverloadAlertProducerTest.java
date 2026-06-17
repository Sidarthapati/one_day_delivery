package com.oneday.grid.events;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.oneday.common.kafka.EventPublisher;
import com.oneday.common.kafka.EventStreams;
import com.oneday.grid.events.payload.TileOverloadAlertEvent;

import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TileOverloadAlertProducerTest {

    @Mock EventPublisher eventPublisher;

    TileOverloadAlertProducer producer;

    final UUID cityId = UUID.randomUUID();
    final UUID tileId = UUID.randomUUID();
    final UUID daId = UUID.randomUUID();
    final LocalDate date = LocalDate.of(2026, 5, 20);

    @BeforeEach
    void setUp() {
        producer = new TileOverloadAlertProducer(eventPublisher);
    }

    // ---- A5 tests ----------------------------------------------------------
    // Best-effort broker-failure handling is covered by EventPublisherTest.

    @Test
    void send_happyPath_publishesToGridExchange() {
        producer.emit(cityId, tileId, daId, date, "WARNING", 5.0, 3, 1.8, 15);

        verify(eventPublisher).publish(eq(EventStreams.GRID_EVENTS), any(TileOverloadAlertEvent.class));
    }
}
