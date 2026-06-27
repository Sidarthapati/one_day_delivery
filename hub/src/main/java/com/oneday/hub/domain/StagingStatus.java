package com.oneday.hub.domain;

/** Delivery-staging lifecycle (§8.2; populated in PR #2). */
public enum StagingStatus {
    STAGED,         // on the delivery stand, awaiting the van
    HANDED_TO_VAN,  // M6 loaded it (driven by the VAN_LOAD scan)
    ON_SHELF,       // hub-collect shelf, awaiting customer
    COLLECTED       // gone
}
