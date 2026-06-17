package com.oneday.grid.events;

import com.oneday.grid.events.payload.TileQueueDepthEvent;
import com.oneday.grid.service.IntradayLoadScoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

// Reads M4's tile-queue-depth feed. The queue (grid.tile-queue-depth, bound to the
// orders.tile_queue_depth exchange) is declared in GridMessagingTopology; until M4 publishes,
// it simply stays empty.
@Component
public class TileQueueDepthConsumer {

    private static final Logger log = LoggerFactory.getLogger(TileQueueDepthConsumer.class);

    private final IntradayLoadScoreService loadScoreService;

    TileQueueDepthConsumer(IntradayLoadScoreService loadScoreService) {
        this.loadScoreService = loadScoreService;
    }

    @RabbitListener(queues = GridMessagingTopology.TILE_QUEUE_DEPTH_QUEUE)
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
