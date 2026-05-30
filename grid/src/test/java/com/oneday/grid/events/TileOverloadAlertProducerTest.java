package com.oneday.grid.events;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import com.oneday.common.kafka.KafkaTopics;

import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TileOverloadAlertProducerTest {

    @Mock KafkaTemplate<String, Object> kafkaTemplate;

    TileOverloadAlertProducer producer;

    final UUID cityId = UUID.randomUUID();
    final UUID tileId = UUID.randomUUID();
    final UUID daId = UUID.randomUUID();
    final LocalDate date = LocalDate.of(2026, 5, 20);

    @BeforeEach
    void setUp() {
        producer = new TileOverloadAlertProducer(kafkaTemplate);
    }

    // ---- A5 tests ----------------------------------------------------------

    @Test
    void send_happyPath_callsKafkaTemplateWithCorrectTopic() {
        producer.emit(cityId, tileId, daId, date, "WARNING", 5.0, 3, 1.8, 15);

        verify(kafkaTemplate).send(eq(KafkaTopics.GRID_EVENTS), eq(tileId.toString()), any());
    }

    @Test
    void send_kafkaTemplateThrows_doesNotPropagateException() {
        doThrow(new RuntimeException("broker down"))
                .when(kafkaTemplate).send(any(String.class), any(String.class), any());

        // Should complete without throwing
        producer.emit(cityId, tileId, daId, date, "CRITICAL", 5.0, 6, 2.5, 10);
    }
}
