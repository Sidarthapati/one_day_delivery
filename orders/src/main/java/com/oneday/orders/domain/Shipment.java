package com.oneday.orders.domain;

import com.oneday.common.domain.MutableBaseEntity;
import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.DropType;
import com.oneday.common.domain.enums.PaymentMode;
import com.oneday.common.domain.enums.PickupType;
import com.oneday.common.domain.enums.ShipmentState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "shipments")
@Getter
@Setter
@NoArgsConstructor
public class Shipment extends MutableBaseEntity {

    @Column(name = "shipment_ref", length = 30, nullable = false, unique = true, updatable = false)
    private String shipmentRef;

    @Enumerated(EnumType.STRING)
    @ColumnTransformer(read = "customer_type::text", write = "CAST(? AS customer_type)")
    @Column(name = "customer_type", nullable = false, updatable = false, columnDefinition = "customer_type")
    private CustomerType customerType;

    @Enumerated(EnumType.STRING)
    @ColumnTransformer(read = "delivery_type::text", write = "CAST(? AS delivery_type)")
    @Column(name = "delivery_type", nullable = false, updatable = false, columnDefinition = "delivery_type")
    private DeliveryType deliveryType;

    @Column(name = "b2b_account_id", updatable = false)
    private UUID b2bAccountId;

    // ── Sender ────────────────────────────────────────────────────────────

    @Column(name = "sender_name", length = 100, nullable = false, updatable = false)
    private String senderName;

    @Column(name = "sender_phone", length = 15, nullable = false, updatable = false)
    private String senderPhone;

    @Column(name = "sender_email", length = 254, updatable = false)
    private String senderEmail;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "origin_address", nullable = false, columnDefinition = "jsonb", updatable = false)
    private Address originAddress;

    @Column(name = "origin_city", length = 10, nullable = false, updatable = false)
    private String originCity;

    @Column(name = "origin_pincode", length = 10, nullable = false, updatable = false)
    private String originPincode;

    // ── Receiver ──────────────────────────────────────────────────────────

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dest_address", nullable = false, columnDefinition = "jsonb", updatable = false)
    private Address destAddress;

    @Column(name = "dest_city", length = 10, nullable = false, updatable = false)
    private String destCity;

    @Column(name = "dest_pincode", length = 10, nullable = false, updatable = false)
    private String destPincode;

    @Column(name = "receiver_name", length = 100, nullable = false, updatable = false)
    private String receiverName;

    @Column(name = "receiver_phone", length = 15, nullable = false, updatable = false)
    private String receiverPhone;

    @Column(name = "receiver_email", length = 254, updatable = false)
    private String receiverEmail;

    // ── Parcel dimensions ─────────────────────────────────────────────────

    @Column(name = "weight_grams", nullable = false, updatable = false)
    private Integer weightGrams;

    @Column(name = "length_cm", nullable = false, updatable = false)
    private Short lengthCm;

    @Column(name = "width_cm", nullable = false, updatable = false)
    private Short widthCm;

    @Column(name = "height_cm", nullable = false, updatable = false)
    private Short heightCm;

    @Column(name = "volumetric_weight_grams", nullable = false, updatable = false)
    private Integer volumetricWeightGrams;

    @Column(name = "chargeable_weight_grams", nullable = false, updatable = false)
    private Integer chargeableWeightGrams;

    // ── Pricing ───────────────────────────────────────────────────────────

    @Column(name = "declared_value_paise", updatable = false)
    private Long declaredValuePaise;

    @Column(name = "quoted_price_paise", nullable = false, updatable = false)
    private Long quotedPricePaise;

    @Column(name = "tax_paise", nullable = false, updatable = false)
    private Long taxPaise;

    @Column(name = "total_price_paise", nullable = false, updatable = false)
    private Long totalPricePaise;

    // finalPricePaise is nullable; set after weight confirmation (post-v1)
    @Column(name = "final_price_paise")
    private Long finalPricePaise;

    @Column(name = "rate_card_version", length = 50, nullable = false, updatable = false)
    private String rateCardVersion;

    // ── Routing ───────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @ColumnTransformer(read = "pickup_type::text", write = "CAST(? AS pickup_type)")
    @Column(name = "pickup_type", nullable = false, columnDefinition = "pickup_type", updatable = false)
    private PickupType pickupType;

    @Enumerated(EnumType.STRING)
    @ColumnTransformer(read = "drop_type::text", write = "CAST(? AS drop_type)")
    @Column(name = "drop_type", nullable = false, columnDefinition = "drop_type", updatable = false)
    private DropType dropType;

    // ── State machine ─────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @ColumnTransformer(read = "state::text", write = "CAST(? AS shipment_state)")
    @Column(name = "state", nullable = false, columnDefinition = "shipment_state")
    private ShipmentState state;

    // ── SLA and ETA ───────────────────────────────────────────────────────

    @Column(name = "sla_commitment_minutes", updatable = false)
    private Short slaCommitmentMinutes;

    @Column(name = "eta_promised")
    private Instant etaPromised;

    @Column(name = "eta_updated")
    private Instant etaUpdated;

    // ── Cross-module references (not DB foreign keys) ─────────────────────

    // Set by M9 once a flight is assigned
    @Column(name = "assigned_flight_id")
    private UUID assignedFlightId;

    // Set from ServiceabilityResult at booking; used by M5 for DA assignment
    @Column(name = "origin_tile_id", updatable = false)
    private UUID originTileId;

    // Null at booking; populated when M8 emits LABEL_GENERATED
    @Column(name = "parcel_id", length = 30)
    private String parcelId;

    // ── Payment ───────────────────────────────────────────────────────────

    // Null for B2B (credit); non-null for B2C/C2C prepaid and COD
    @Enumerated(EnumType.STRING)
    @ColumnTransformer(read = "payment_mode::text", write = "CAST(? AS payment_mode)")
    @Column(name = "payment_mode", columnDefinition = "payment_mode", updatable = false)
    private PaymentMode paymentMode;

    // FK to payment_transactions; null for B2B and COD-before-delivery
    @Column(name = "payment_id")
    private UUID paymentId;

    // ── Idempotency ───────────────────────────────────────────────────────

    @Column(name = "idempotency_key", length = 100, updatable = false)
    private String idempotencyKey;

    // ── Cancellation ──────────────────────────────────────────────────────

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    // ── Archival ──────────────────────────────────────────────────────────

    // Set by nightly ArchivalJob after 2 years; never deleted
    @Column(name = "archived_at")
    private Instant archivedAt;

    // ── Auth scope ────────────────────────────────────────────────────────

    // Origin city code; used for city-scoped permission enforcement
    @Column(name = "city_id", length = 10, nullable = false, updatable = false)
    private String cityId;
}
