package com.oneday.dispatch.domain;

import com.oneday.common.domain.MutableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * An order held out of the DA queue until its pickup slot is near. The release job assigns it (normal
 * pipeline) once {@code release_at} passes, then flips it to RELEASED. HELD → CANCELLED if the shipment
 * is cancelled while waiting.
 */
@Entity
@Table(name = "scheduled_pickup")
@Getter
@Setter
@NoArgsConstructor
public class ScheduledPickup extends MutableBaseEntity {

    @Column(name = "shipment_id", nullable = false, updatable = false)
    private UUID shipmentId;

    // Parent order carried through the hold so the released pickup groups with its siblings (M4 Order → N).
    @Column(name = "order_id", updatable = false)
    private UUID orderId;

    @Column(name = "order_ref", length = 30, updatable = false)
    private String orderRef;

    @Column(name = "city_id", nullable = false, updatable = false)
    private UUID cityId;

    @Column(name = "tile_id", nullable = false, updatable = false)
    private UUID tileId;

    @Column(name = "pickup_lat", nullable = false, updatable = false)
    private double pickupLat;

    @Column(name = "pickup_lon", nullable = false, updatable = false)
    private double pickupLon;

    @Column(name = "payment_mode", length = 20, updatable = false)
    private String paymentMode;

    @Column(name = "slot_start", updatable = false)
    private Instant slotStart;

    @Column(name = "slot_end", updatable = false)
    private Instant slotEnd;

    @Column(name = "release_at", nullable = false)
    private Instant releaseAt;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "HELD";

    @Column(name = "released_at")
    private Instant releasedAt;
}
