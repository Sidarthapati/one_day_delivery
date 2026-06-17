package com.oneday.grid.events;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.oneday.common.kafka.EventPublisher;
import com.oneday.common.kafka.EventStreams;
import com.oneday.grid.events.payload.NoDaAlertEvent;

import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NoDaAlertProducerTest {

    @Mock EventPublisher eventPublisher;

    NoDaAlertProducer producer;

    final UUID cityId = UUID.randomUUID();
    final UUID tileId = UUID.randomUUID();
    final LocalDate date = LocalDate.of(2026, 5, 20);

    @BeforeEach
    void setUp() {
        producer = new NoDaAlertProducer(eventPublisher);
    }

    // ---- A5 tests ----------------------------------------------------------
    // Best-effort broker-failure handling is covered by EventPublisherTest; this producer is a
    // thin pass-through onto the shared EventPublisher port.

    @Test
    void send_happyPath_publishesToGridExchange() {
        producer.emit(cityId, tileId, date, "NO_DA_AVAILABLE");

        verify(eventPublisher).publish(eq(EventStreams.GRID_EVENTS), any(NoDaAlertEvent.class));
    }
}
