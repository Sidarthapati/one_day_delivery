package com.oneday.assets.domain;

/** Where an asset is in its life. IN_STOCK = at the station store; ASSIGNED = held by a person. */
public enum AssetStatus {
    IN_STOCK, ASSIGNED, IN_MAINTENANCE, LOST, DAMAGED, RETIRED
}
