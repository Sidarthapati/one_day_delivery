package com.oneday.orders.domain;

import com.oneday.common.domain.MutableBaseEntity;
import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.DropType;
import com.oneday.common.domain.enums.PickupType;
import com.oneday.orders.domain.enums.CartItemSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * One complete, independent shipment draft inside a {@link Cart}. Mirrors the fields of a
 * booking request; serviceability/pricing are cached for display and re-validated at checkout.
 */
@Entity
@Table(name = "cart_item")
@Getter
@Setter
@NoArgsConstructor
public class CartItem extends MutableBaseEntity {

    @Column(name = "cart_id", nullable = false, updatable = false)
    private UUID cartId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 8, nullable = false)
    private CartItemSource source = CartItemSource.MANUAL;

    @Column(name = "excel_row_num")
    private Integer excelRowNum;

    // ── Pickup ──────────────────────────────────────────────────────────────
    @Column(name = "sender_name", length = 100, nullable = false)
    private String senderName;
    @Column(name = "sender_phone", length = 15, nullable = false)
    private String senderPhone;
    @Column(name = "sender_email", length = 254)
    private String senderEmail;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "origin_address", nullable = false, columnDefinition = "jsonb")
    private Address originAddress;
    @Column(name = "origin_city", length = 100, nullable = false)
    private String originCity;
    @Column(name = "origin_pincode", length = 10, nullable = false)
    private String originPincode;

    // ── Drop ────────────────────────────────────────────────────────────────
    @Column(name = "receiver_name", length = 100, nullable = false)
    private String receiverName;
    @Column(name = "receiver_phone", length = 15, nullable = false)
    private String receiverPhone;
    @Column(name = "receiver_email", length = 254)
    private String receiverEmail;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dest_address", nullable = false, columnDefinition = "jsonb")
    private Address destAddress;
    @Column(name = "dest_city", length = 100, nullable = false)
    private String destCity;
    @Column(name = "dest_pincode", length = 10, nullable = false)
    private String destPincode;

    // ── Parcel ──────────────────────────────────────────────────────────────
    @Column(name = "weight_grams", nullable = false)
    private int weightGrams;
    @Column(name = "length_cm", nullable = false)
    private short lengthCm;
    @Column(name = "width_cm", nullable = false)
    private short widthCm;
    @Column(name = "height_cm", nullable = false)
    private short heightCm;
    @Column(name = "declared_value_paise")
    private Long declaredValuePaise;
    @Enumerated(EnumType.STRING)
    @Column(name = "pickup_type", length = 16, nullable = false)
    private PickupType pickupType;
    @Enumerated(EnumType.STRING)
    @Column(name = "drop_type", length = 16, nullable = false)
    private DropType dropType;

    // ── Cached compute ──────────────────────────────────────────────────────
    @Column(name = "origin_tile_id")
    private UUID originTileId;
    @Column(name = "dest_tile_id")
    private UUID destTileId;
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_type", length = 16)
    private DeliveryType deliveryType;
    @Column(name = "quoted_total_paise")
    private Long quotedTotalPaise;
    @Column(name = "validation_status", length = 8, nullable = false)
    private String validationStatus = "VALID";
    @Column(name = "booked_shipment_ref", length = 64)
    private String bookedShipmentRef;
}
