package com.oneday.grid.events;

import com.oneday.grid.events.payload.TileQueueDepthEvent;
import com.oneday.grid.service.IntradayLoadScoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

// autoStartup = false until M4 is publishing on orders.tile_queue_depth.
// Flip grid.kafka.consumer.auto-startup=true in application.yml to enable.
@Component
public class TileQueueDepthConsumer {

    private static final Logger log = LoggerFactory.getLogger(TileQueueDepthConsumer.class);

    private final IntradayLoadScoreService loadScoreService;

    TileQueueDepthConsumer(IntradayLoadScoreService loadScoreService) {
        this.loadScoreService = loadScoreService;
    }

    @KafkaListener(
            topics = KafkaTopics.TILE_QUEUE_DEPTH,
            groupId = "grid-service",
            autoStartup = "${grid.kafka.consumer.auto-startup:false}"
    )
    public void onQueueDepth(TileQueueDepthEvent event) {
        log.debug("TILE_QUEUE_DEPTH tileId={} unserved={} booked={} date={}",
                event.tileId(), event.unservedOrders(), event.bookedOrders(), event.date());
        loadScoreService.updateQueueDepth(
                event.cityId(),
                event.date(),
                Map.of(event.tileId(), event.unservedOrders())
        );
    }
}
