package com.oneday.grid.events;

import com.oneday.grid.events.payload.NoDaAlertEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Component
public class NoDaAlertProducer {

    private static final Logger log = LoggerFactory.getLogger(NoDaAlertProducer.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    NoDaAlertProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void emit(UUID cityId, UUID tileId, LocalDate validDate, String reason) {
        NoDaAlertEvent event = new NoDaAlertEvent(cityId, tileId, validDate, reason, Instant.now());
        try {
            kafkaTemplate.send(KafkaTopics.NO_DA_ALERT, tileId.toString(), event);
        } catch (Exception e) {
            log.warn("NO_DA_ALERT kafka send failed — city={} tile={} date={} reason={}: {}",
                    cityId, tileId, validDate, reason, e.getMessage());
        }
    }
}
