package com.oneday.hub.domain;

/** Delivery-bag-item lifecycle (§8.2; the delivery_bag unit lands in PR #2). */
public enum DeliveryBagItemStatus {
    STAGED,    // in the delivery bag on its stand, awaiting the van
    LOADED,    // the van loaded the bag (driven by M6's VAN_LOAD scan)
    ON_SHELF,  // hub-collect shelf, awaiting customer
    COLLECTED  // gone
}
