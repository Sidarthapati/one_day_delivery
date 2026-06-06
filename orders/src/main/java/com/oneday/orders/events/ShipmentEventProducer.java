package com.oneday.orders.events;

import com.oneday.common.kafka.EventPublisher;
import com.oneday.common.kafka.KafkaTopics;
import com.oneday.common.kafka.enums.ShipmentEventType;
import com.oneday.common.kafka.events.ShipmentCancelledEvent;
import com.oneday.common.kafka.events.ShipmentCreatedEvent;
import com.oneday.common.kafka.events.ShipmentStateChangedEvent;
import com.oneday.orders.domain.Shipment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.UUID;

/**
 * M4's outbound Kafka producer. Listens for in-process {@link ShipmentTransitioned} events
 * (published by the state machine) and emits one Kafka STATE_CHANGED event per committed
 * transition to {@link KafkaTopics#SHIPMENTS_EVENTS}.
 *
 * <p><b>AFTER_COMMIT:</b> the Kafka publish happens only once the DB transaction has
 * committed, so a rolled-back transition never produces a phantom event. {@link EventPublisher}
 * is best-effort — a broker hiccup is logged, not thrown, and never affects the (already
 * committed) shipment state.</p>
 *
 * <p>CREATED and the rich CANCELLED events are emitted separately by the booking and
 * cancellation flows (which know payment/refund details the state machine does not) — they
 * are not derived from this transition hook.</p>
 */
@Component
public class ShipmentEventProducer {

    private static final Logger log = LoggerFactory.getLogger(ShipmentEventProducer.class);

    private final EventPublisher eventPublisher;

    ShipmentEventProducer(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onShipmentTransitioned(ShipmentTransitioned e) {
        ShipmentStateChangedEvent event = new ShipmentStateChangedEvent();
        event.setEventId(UUID.randomUUID());
        event.setEventType(ShipmentEventType.STATE_CHANGED);
        event.setOccurredAt(e.occurredAt() != null ? e.occurredAt() : Instant.now());
        event.setShipmentId(e.shipmentId());
        event.setShipmentRef(e.shipmentRef());
        event.setFromState(e.fromState());
        event.setToState(e.toState());
        event.setTriggeredBy(e.triggeredBy());
        event.setTriggerSource(e.triggerSource() != null ? e.triggerSource().name() : null);

        log.debug("Publishing STATE_CHANGED shipmentId={} {}->{}",
                e.shipmentId(), e.fromState(), e.toState());
        eventPublisher.publish(KafkaTopics.SHIPMENTS_EVENTS, event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onShipmentBooked(ShipmentBooked e) {
        Shipment s = e.shipment();
        ShipmentCreatedEvent event = new ShipmentCreatedEvent();
        event.setEventId(UUID.randomUUID());
        event.setEventType(ShipmentEventType.CREATED);
        event.setOccurredAt(s.getCreatedAt() != null ? s.getCreatedAt() : Instant.now());
        event.setShipmentId(s.getId());
        event.setShipmentRef(s.getShipmentRef());
        event.setCustomerType(s.getCustomerType());
        event.setPaymentMode(s.getPaymentMode());
        event.setDeliveryType(s.getDeliveryType());
        event.setPickupType(s.getPickupType());
        event.setDropType(s.getDropType());
        event.setOriginCity(s.getOriginCity());
        event.setOriginPincode(s.getOriginPincode());
        event.setOriginTileId(s.getOriginTileId());
        event.setDestCity(s.getDestCity());
        event.setDestPincode(s.getDestPincode());
        event.setDestTileId(s.getDestTileId());
        event.setChargeableWeightGrams(s.getChargeableWeightGrams());
        event.setSlaCommitmentMinutes(s.getSlaCommitmentMinutes() != null
                ? s.getSlaCommitmentMinutes().intValue() : null);
        event.setEtaPromised(s.getEtaPromised());
        event.setReceiverPhone(s.getReceiverPhone());
        event.setReceiverName(s.getReceiverName());
        event.setB2bAccountId(s.getB2bAccountId());
        event.setSenderName(s.getSenderName());
        event.setSenderAddressLine(s.getOriginAddress() != null ? s.getOriginAddress().getLine1() : null);
        event.setReceiverAddressLine(s.getDestAddress() != null ? s.getDestAddress().getLine1() : null);

        log.debug("Publishing CREATED shipmentId={} ref={}", s.getId(), s.getShipmentRef());
        eventPublisher.publish(KafkaTopics.SHIPMENTS_EVENTS, event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onShipmentCancelled(ShipmentCancelled e) {
        ShipmentCancelledEvent event = new ShipmentCancelledEvent();
        event.setEventId(UUID.randomUUID());
        event.setEventType(ShipmentEventType.CANCELLED);
        event.setOccurredAt(e.occurredAt() != null ? e.occurredAt() : Instant.now());
        event.setShipmentId(e.shipmentId());
        event.setShipmentRef(e.shipmentRef());
        event.setCancelledAtState(e.cancelledAtState());
        event.setReason(e.reason());
        event.setRefundInitiated(e.refundInitiated());
        event.setRefundAmountPaise(e.refundAmountPaise());

        log.debug("Publishing CANCELLED shipmentId={} ref={} cancelledAtState={}",
                e.shipmentId(), e.shipmentRef(), e.cancelledAtState());
        eventPublisher.publish(KafkaTopics.SHIPMENTS_EVENTS, event);
    }
}
