package com.oneday.dispatch.events;

import com.oneday.common.kafka.EventPublisher;
import com.oneday.common.kafka.EventStreams;
import com.oneday.common.kafka.enums.DaEventType;
import com.oneday.common.kafka.events.DaLifecycleEvent;
import com.oneday.dispatch.config.DispatchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * The single place M5 emits {@link DaLifecycleEvent}s on {@code oneday.da.events}.
 *
 * <p><b>Gated by {@code dispatch.events.publish-da-events} (default false).</b> M6's
 * {@code DaFeedConsumer} catch-all-binds this exchange and would mis-read every DA event as a
 * collected parcel (plan addendum §7). Until that contract is settled the producer logs the event
 * instead of publishing, so the job logic (ABSENT detection, shift-end deferral) is fully exercised
 * without poisoning M6. Flip the flag on once M6 narrows its binding.</p>
 */
@Component
public class DaEventProducer {

    private static final Logger log = LoggerFactory.getLogger(DaEventProducer.class);

    private final EventPublisher eventPublisher;
    private final DispatchProperties props;

    public DaEventProducer(EventPublisher eventPublisher, DispatchProperties props) {
        this.eventPublisher = eventPublisher;
        this.props = props;
    }

    /** DA went silent past the heartbeat threshold and was marked ABSENT. → M10, M11. */
    public void emitDaAbsent(UUID daId, UUID cityId) {
        emit(DaEventType.DA_ABSENT, daId, cityId, null, null, "HEARTBEAT_LAPSED");
    }

    /** A QUEUED task could not be worked before shift end and was deferred. → M11. */
    public void emitTaskDeferredShiftEnded(UUID daId, UUID cityId, UUID shipmentId) {
        emit(DaEventType.TASK_DEFERRED_SHIFT_ENDED, daId, cityId, shipmentId, null, "SHIFT_ENDED");
    }

    private void emit(DaEventType type, UUID daId, UUID cityId, UUID shipmentId,
                      String shipmentRef, String reasonCode) {
        DaLifecycleEvent event = new DaLifecycleEvent(
                UUID.randomUUID(), type, DaLifecycleEvent.SCHEMA_VERSION, Instant.now(),
                shipmentId, shipmentRef, daId, cityId, null, null, reasonCode);

        if (!props.getEvents().isPublishDaEvents()) {
            log.debug("DA event {} for da {} suppressed (dispatch.events.publish-da-events=false)", type, daId);
            return;
        }
        eventPublisher.publish(EventStreams.DA_EVENTS, event);
    }
}
