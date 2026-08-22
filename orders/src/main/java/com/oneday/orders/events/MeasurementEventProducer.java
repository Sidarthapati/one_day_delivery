package com.oneday.orders.events;

import com.oneday.common.kafka.EventPublisher;
import com.oneday.common.kafka.EventStreams;
import com.oneday.common.kafka.enums.MeasurementEventType;
import com.oneday.common.kafka.events.ParcelMeasuredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Relays an in-process {@link ParcelMeasured} to {@link EventStreams#SHIPMENTS_EVENTS} once the
 * measurement transaction commits. An over-declared result routes as
 * {@code DIMENSION_DISCREPANCY_FLAGGED} (the hook for a future chargeback/ops flow); otherwise
 * {@code MEASUREMENT_RECORDED}. Publishing is best-effort — a broker hiccup is logged, not thrown.
 */
@Component
public class MeasurementEventProducer {

    private static final Logger log = LoggerFactory.getLogger(MeasurementEventProducer.class);

    private final EventPublisher eventPublisher;

    MeasurementEventProducer(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onParcelMeasured(ParcelMeasured e) {
        MeasurementEventType type = e.overDeclared()
                ? MeasurementEventType.DIMENSION_DISCREPANCY_FLAGGED
                : MeasurementEventType.MEASUREMENT_RECORDED;
        ParcelMeasuredEvent event = new ParcelMeasuredEvent(
                UUID.randomUUID(), type,
                e.occurredAt() != null ? e.occurredAt() : Instant.now(),
                e.shipmentId(), e.shipmentRef(), e.source(), e.status(),
                e.lengthCm(), e.widthCm(), e.heightCm(), e.volumetricWeightGrams(),
                e.declaredLengthCm(), e.declaredWidthCm(), e.declaredHeightCm(),
                e.overDeclared(), e.discrepancyDetail(), e.measuredBy());
        log.debug("Publishing {} shipmentRef={} overDeclared={}", type, e.shipmentRef(), e.overDeclared());
        eventPublisher.publish(EventStreams.SHIPMENTS_EVENTS, event);
    }
}
