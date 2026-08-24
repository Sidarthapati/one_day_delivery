package com.oneday.orders.service;

import com.oneday.common.domain.enums.CustomerType;

import java.util.UUID;

/**
 * Mints and maintains the parent {@link com.oneday.orders.domain.ParcelOrder} of the
 * Order → N Shipments abstraction. Booking services call {@link #createOrder} once per booking
 * (or once per cart checkout) and {@link #addShipment} as each child shipment is persisted.
 */
public interface OrderService {

    /**
     * Creates an empty parent order (count 0, total 0) and returns its id + ref.
     * Must be called inside an active transaction (the ref counter increment is MANDATORY).
     *
     * @param customerType     B2C / C2C / B2B
     * @param b2bAccountId     the B2B account, or null for retail
     * @param userId           the M1 user placing the order (string; parsed leniently)
     * @param originCityCode   origin city (drives the ref + the order's city scope)
     * @param purchaseOrderRef the merchant's own PO ref, or null
     */
    CreatedOrder createOrder(CustomerType customerType, UUID b2bAccountId, String userId,
                             String originCityCode, String purchaseOrderRef);

    /** Atomically folds one booked shipment into the order rollup (count + 1, total += amount). */
    void addShipment(UUID orderId, long shipmentTotalPaise);

    record CreatedOrder(UUID id, String orderRef) {}
}
