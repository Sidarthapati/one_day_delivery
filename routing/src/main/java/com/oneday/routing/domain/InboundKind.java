package com.oneday.routing.domain;

// Which feed an accumulated parcel came from (§12.1 / §12.2).
public enum InboundKind {
    DELIVER,   // from M7: sorted for delivery
    COLLECT    // from M5: picked up by a DA
}
