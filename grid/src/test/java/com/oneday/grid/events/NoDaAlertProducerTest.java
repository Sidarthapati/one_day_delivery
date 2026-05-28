package com.oneday.grid.events;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NoDaAlertProducerTest {

    @Mock KafkaTemplate<String, Object> kafkaTemplate;

    NoDaAlertProducer producer;

    final UUID cityId = UUID.randomUUID();
    final UUID tileId = UUID.randomUUID();
    final LocalDate date = LocalDate.of(2026, 5, 20);

    @BeforeEach
    void setUp() {
        producer = new NoDaAlertProducer(kafkaTemplate);
    }

    // ---- A5 tests ----------------------------------------------------------

    @Test
    void send_happyPath_callsKafkaTemplateWithCorrectTopic() {
        producer.emit(cityId, tileId, date, "NO_DA_AVAILABLE");

        verify(kafkaTemplate).send(eq(KafkaTopics.NO_DA_ALERT), eq(tileId.toString()), any());
    }

    @Test
    void send_kafkaTemplateThrows_doesNotPropagateException() {
        doThrow(new RuntimeException("broker down"))
                .when(kafkaTemplate).send(any(String.class), any(String.class), any());

        // Should complete without throwing
        producer.emit(cityId, tileId, date, "NO_DA_AVAILABLE");
    }
}
