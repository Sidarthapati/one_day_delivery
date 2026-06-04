package com.oneday.orders.events;

import com.oneday.orders.domain.Shipment;

/**
 * In-process (NOT Kafka) Spring {@code ApplicationEvent}, published by
 * {@code BookingServiceImpl} after a shipment is persisted in {@code BOOKED} state.
 *
 * <p>{@link ShipmentEventProducer} maps it to the outbound Kafka {@code ShipmentCreatedEvent}
 * after the booking transaction commits (AFTER_COMMIT).</p>
 *
 * <p>Shipment creation is <em>not</em> a state-machine transition ({@code BOOKED} is a birth
 * state, not a {@code from → to} move), so {@code CREATED} cannot ride {@link ShipmentTransitioned} —
 * it needs this dedicated hook.</p>
 *
 * <p>The {@link Shipment} is detached by the time the AFTER_COMMIT listener reads it; that is
 * safe here because all of its fields are plain columns / embeddables loaded eagerly within the
 * booking transaction — {@code Shipment} has no lazy associations.</p>
 */
public record ShipmentBooked(Shipment shipment) {}
