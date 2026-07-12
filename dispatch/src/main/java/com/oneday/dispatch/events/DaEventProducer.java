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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * The single place M5 emits {@link DaLifecycleEvent}s on {@code oneday.da.events}.
 *
 * <p><b>Gated by {@code dispatch.events.publish-da-events} (default true).</b> The M5↔M6 contract is
 * settled: {@link DaLifecycleEvent} is the single rich type on {@code DA_EVENTS}, and both consumers
 * (M4 {@code DaEventsConsumer}, M6 {@code DaFeedConsumer}) take it and dispatch by {@code eventType}
 * (M6 acts only on {@code PICKUP_COMPLETED}). The catch-all binding is therefore safe. The flag
 * remains as a kill-switch; set it false to suppress publishing (the event is logged instead, so the
 * job logic — ABSENT detection, shift-end deferral — is still exercised).</p>
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

    /** A DA's queue order changed (a task was cancelled/removed and the rest resequenced). → ops/UI. */
    public void emitQueueReordered(UUID daId, UUID cityId) {
        emit(DaEventType.QUEUE_REORDERED, daId, cityId, null, null, null);
    }

    /** M5 assigned the first-mile pickup to a DA. → M4 (BOOKED → PICKUP_ASSIGNED, mints the pickup OTP). */
    public void emitPickupAssigned(UUID daId, UUID cityId, UUID shipmentId) {
        emit(DaEventType.PICKUP_ASSIGNED, daId, cityId, shipmentId, null, null);
    }

    /** M5 assigned the last-mile delivery to a DA. → M4 (HANDED_TO_DROP_VAN → DROP_ASSIGNED). */
    public void emitDropAssigned(UUID daId, UUID cityId, UUID shipmentId) {
        emit(DaEventType.DROP_ASSIGNED, daId, cityId, shipmentId, null, null);
    }

    /** Pickup OTP verified and the shipment moved to PICKED_UP. → M10 (SLA leg start). */
    public void emitPickupCompleted(UUID daId, UUID cityId, UUID shipmentId) {
        emit(DaEventType.PICKUP_COMPLETED, daId, cityId, shipmentId, null, null);
    }

    /** DA handed the picked-up parcel to the cron van. → M4 (HANDED_TO_PICKUP_VAN), M10. */
    public void emitVanHandoffCompleted(UUID daId, UUID cityId, UUID shipmentId) {
        emit(DaEventType.VAN_HANDOFF_COMPLETED, daId, cityId, shipmentId, null, null);
    }

    /** HUB_RETURN city: DA dropped the picked-up parcel at the hub (no van). → M4 (HANDED_TO_PICKUP_VAN), M10. */
    public void emitHubReturnHandoffCompleted(UUID daId, UUID cityId, UUID shipmentId) {
        emit(DaEventType.HUB_RETURN_HANDOFF_COMPLETED, daId, cityId, shipmentId, null, null);
    }

    /** A pickup could not be completed by the DA. → M4 (PICKUP_FAILED), M11. */
    public void emitPickupFailed(UUID daId, UUID cityId, UUID shipmentId, String reason) {
        emit(DaEventType.PICKUP_FAILED, daId, cityId, shipmentId, null, reason);
    }

    /** A delivery could not be completed by the DA. → M4 (DELIVERY_FAILED), M11. */
    public void emitDropFailed(UUID daId, UUID cityId, UUID shipmentId, String reason) {
        emit(DaEventType.DROP_FAILED, daId, cityId, shipmentId, null, reason);
    }

    /** DA collected the parcel from the cron van for last-mile delivery. → M4 (DROP_COLLECTED). */
    public void emitDropCollected(UUID daId, UUID cityId, UUID shipmentId) {
        emit(DaEventType.DROP_COLLECTED, daId, cityId, shipmentId, null, null);
    }

    /** DA delivered the parcel to the receiver. → M4 (DROPPED), M10. */
    public void emitDropCompleted(UUID daId, UUID cityId, UUID shipmentId) {
        emit(DaEventType.DROP_COMPLETED, daId, cityId, shipmentId, null, null);
    }

    /** COD cash collected at delivery; emitted alongside DROP_COMPLETED. → finance / M10. */
    public void emitCodCollected(UUID daId, UUID cityId, UUID shipmentId) {
        emit(DaEventType.COD_COLLECTED, daId, cityId, shipmentId, null, null);
    }

    private void emit(DaEventType type, UUID daId, UUID cityId, UUID shipmentId,
                      String shipmentRef, String reasonCode) {
        // v1: parcel id == shipment id (no M8 barcode yet); validDate = operating date in the shift zone.
        // M6's collect-bind reads parcelId + validDate; both are null-safe for DA-scoped events.
        UUID parcelId = shipmentId;
        LocalDate validDate = shipmentId != null
                ? LocalDate.now(ZoneId.of(props.getShift().getZone())) : null;
        DaLifecycleEvent event = new DaLifecycleEvent(
                UUID.randomUUID(), type, DaLifecycleEvent.SCHEMA_VERSION, Instant.now(),
                shipmentId, shipmentRef, daId, cityId, null, null, reasonCode, parcelId, validDate);

        if (!props.getEvents().isPublishDaEvents()) {
            log.debug("DA event {} for da {} suppressed (dispatch.events.publish-da-events=false)", type, daId);
            return;
        }
        eventPublisher.publish(EventStreams.DA_EVENTS, event);
    }
}
