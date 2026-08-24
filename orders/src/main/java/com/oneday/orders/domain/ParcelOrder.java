package com.oneday.orders.domain;

import com.oneday.common.domain.MutableBaseEntity;
import com.oneday.common.domain.enums.CustomerType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnTransformer;

import java.util.UUID;

/**
 * The durable parent of the Order → N Shipments abstraction. Every booking creates or joins one:
 * a single booking → an order of one; a cart checkout / bulk upload → one order over all its
 * shipments. Unlike the transient {@link Cart} (a pre-checkout basket that is emptied at checkout),
 * a {@code ParcelOrder} survives and each {@link Shipment} carries its {@code order_id}.
 *
 * <p>An order is a <em>booking</em> grouping, not a routing unit — its shipments may ride different
 * flights / delivery DAs. {@code parcelCount} and {@code totalPricePaise} are a denormalized rollup,
 * advanced atomically as each shipment is booked (see {@code ParcelOrderRepository.addShipment}).</p>
 */
@Entity
@Table(name = "parcel_orders")
@Getter
@Setter
@NoArgsConstructor
public class ParcelOrder extends MutableBaseEntity {

    @Column(name = "order_ref", length = 30, nullable = false, unique = true, updatable = false)
    private String orderRef;

    @Enumerated(EnumType.STRING)
    @ColumnTransformer(read = "customer_type::text", write = "CAST(? AS customer_type)")
    @Column(name = "customer_type", nullable = false, updatable = false, columnDefinition = "customer_type")
    private CustomerType customerType;

    @Column(name = "b2b_account_id", updatable = false)
    private UUID b2bAccountId;

    @Column(name = "booked_by_user_id", updatable = false)
    private UUID bookedByUserId;

    @Column(name = "purchase_order_ref", length = 100, updatable = false)
    private String purchaseOrderRef;

    @Column(name = "parcel_count", nullable = false)
    private int parcelCount;

    @Column(name = "total_price_paise", nullable = false)
    private long totalPricePaise;

    @Column(name = "city_id", length = 10, nullable = false, updatable = false)
    private String cityId;
}
