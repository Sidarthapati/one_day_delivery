package com.oneday.grid.events;

import com.oneday.grid.events.payload.TileOverloadAlertEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Component
public class TileOverloadAlertProducer {

    private static final Logger log = LoggerFactory.getLogger(TileOverloadAlertProducer.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    TileOverloadAlertProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void emit(UUID cityId, UUID tileId, UUID daId, LocalDate date,
                     String severity, double expectedOrders, int unservedOrders,
                     double adjustedScore, int sustainedMinutes) {
        TileOverloadAlertEvent event = new TileOverloadAlertEvent(
                cityId, tileId, daId, date, severity,
                expectedOrders, unservedOrders, adjustedScore, sustainedMinutes,
                Instant.now()
        );
        try {
            kafkaTemplate.send(KafkaTopics.TILE_OVERLOAD_ALERT, tileId.toString(), event);
        } catch (Exception e) {
            log.warn("TILE_OVERLOAD_ALERT kafka send failed — tile={} severity={}: {}",
                    tileId, severity, e.getMessage());
        }
    }
}
