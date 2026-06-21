package com.oneday.dispatch.batch;

import com.oneday.common.kafka.EventStreams;
import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import com.oneday.dispatch.repository.TileDepthCount;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.grid.events.payload.TileQueueDepthEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Periodically feeds M3 the per-tile queue depth so it can refresh load scores and raise overload
 * alerts (design §16.3). Runs only while a shift is loaded (no DAs in memory → no-op). Counts come
 * from the DB grouped by tile: {@code unservedOrders} = QUEUED, {@code bookedOrders} = QUEUED +
 * IN_PROGRESS.
 *
 * <p><b>Gated by {@code dispatch.events.publish-tile-queue-depth} (default false)</b> until M4-vs-M5
 * ownership of {@code orders.tile_queue_depth} is settled (plan addendum §4); the job still computes
 * and logs so the aggregation is exercised. Publishes M3's own {@link TileQueueDepthEvent} type so its
 * consumer deserializes it via the {@code __TypeId__} header.</p>
 */
@Component
public class TileQueueDepthPublisher {

    private static final Logger log = LoggerFactory.getLogger(TileQueueDepthPublisher.class);
    private static final String ROUTING_KEY = "tile.queue.depth";

    private final DispatchQueueRepository queueRepository;
    private final DaStatusService daStatusService;
    private final RabbitTemplate rabbitTemplate;
    private final DispatchProperties props;

    public TileQueueDepthPublisher(DispatchQueueRepository queueRepository,
                                   DaStatusService daStatusService,
                                   RabbitTemplate rabbitTemplate,
                                   DispatchProperties props) {
        this.queueRepository = queueRepository;
        this.daStatusService = daStatusService;
        this.rabbitTemplate = rabbitTemplate;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${dispatch.monitor.interval-seconds:300}", timeUnit = TimeUnit.SECONDS)
    @Transactional(readOnly = true)
    public void publish() {
        if (daStatusService.loadedDaIds().isEmpty()) {
            return;   // outside shift hours — nothing loaded
        }
        LocalDate today = LocalDate.now(ZoneId.of(props.getShift().getZone()));

        // (city, tile) → [unserved, booked]
        Map<TileKey, int[]> depths = new HashMap<>();
        for (TileDepthCount row : queueRepository.activeDepthByTile(today)) {
            int[] counts = depths.computeIfAbsent(new TileKey(row.cityId(), row.tileId()), k -> new int[2]);
            counts[1] += (int) row.count();                       // booked = all active
            if (row.status() == TaskStatus.QUEUED) {
                counts[0] += (int) row.count();                   // unserved = QUEUED
            }
        }
        if (depths.isEmpty()) {
            return;
        }

        boolean publish = props.getEvents().isPublishTileQueueDepth();
        Instant now = Instant.now();
        for (Map.Entry<TileKey, int[]> e : depths.entrySet()) {
            TileQueueDepthEvent event = new TileQueueDepthEvent(
                    e.getKey().tileId(), e.getKey().cityId(), today, e.getValue()[0], e.getValue()[1], now);
            if (publish) {
                rabbitTemplate.convertAndSend(EventStreams.TILE_QUEUE_DEPTH, ROUTING_KEY, event);
            } else {
                log.debug("tile-queue-depth suppressed (flag off): {}", event);
            }
        }
        log.debug("Tile queue depth: {} tiles{}", depths.size(), publish ? " published" : " (suppressed)");
    }

    private record TileKey(UUID cityId, UUID tileId) {}
}
