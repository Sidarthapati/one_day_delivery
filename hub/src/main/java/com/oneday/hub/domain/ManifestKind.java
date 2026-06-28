package com.oneday.hub.domain;

/** What a bag_manifest packs (§14.3). One shared, append-only manifest model, two shapes. */
public enum ManifestKind {
    FLIGHT,    // OUTBOUND: the airline packing list for a flight bag (§7.3)
    LOAD_LIST  // INBOUND: the van/DA load list for a delivery bag (§8.1)
}
