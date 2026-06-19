package com.oneday.routing.events;

import com.oneday.common.kafka.EventPublisher;
import com.oneday.common.kafka.EventStreams;
import com.oneday.common.kafka.events.cron.VanArrivedEvent;
import com.oneday.common.kafka.events.cron.VanRunningLateEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes M6's run-time tracking events on {@code oneday.cron.events} (§14.4, §17.1). Raw GPS
 * pings never reach the bus (M6-D-012) — only these two: VAN_ARRIVED when a van reaches a meeting
 * vertex, and VAN_RUNNING_LATE when its lateness crosses the threshold. → M5 (re-check cron
 * feasibility), M10 (SLA), ops map.
 */
@Component
public class RouteDeviationProducer {

    private final EventPublisher eventPublisher;

    RouteDeviationProducer(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /** VAN_ARRIVED → M10, ops: the van reached a meeting vertex. */
    public void emitVanArrived(UUID vanId, UUID cityId, UUID routePlanId, int loopIndex, int stopSeq,
                               UUID hexVertexId, Instant arrivedAt) {
        eventPublisher.publish(EventStreams.CRON_EVENTS,
                new VanArrivedEvent(vanId, cityId, routePlanId, loopIndex, stopSeq, hexVertexId, arrivedAt));
    }

    /** VAN_RUNNING_LATE → M5, M10, ops: live ETA slipped past the lateness threshold. */
    public void emitVanRunningLate(UUID vanId, UUID cityId, UUID routePlanId, int loopIndex, int stopSeq,
                                   int minutesLate) {
        eventPublisher.publish(EventStreams.CRON_EVENTS,
                new VanRunningLateEvent(vanId, cityId, routePlanId, loopIndex, stopSeq, minutesLate));
    }
}
