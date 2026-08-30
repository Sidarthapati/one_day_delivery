package com.oneday.orders.service;

import com.oneday.orders.domain.ParcelOrder;

/**
 * Gate for adding a parcel to an order whose pickup is already in progress: if a DA is on the pickup,
 * the parcel must fit within that DA's vehicle capacity. Purely a safety check on the "add shipment to
 * an existing order" path — when no DA is assigned yet, dispatch places the parcel normally and there
 * is nothing to weigh against.
 */
public interface OrderCapacityService {

    /**
     * Throws {@link DaCapacityExceededException} if the order's assigned pickup DA cannot carry
     * {@code newParcelChargeableGrams} more on top of what it is already committed to. No-op when no DA
     * is assigned to the order's pickup, or when the dispatch load port is unavailable.
     */
    void ensureCapacityForAdd(ParcelOrder order, int newParcelChargeableGrams);

    /** The assigned DA's vehicle is already loaded to capacity for today's pickup → 409. */
    class DaCapacityExceededException extends RuntimeException {
        public DaCapacityExceededException(String message) { super(message); }
    }
}
