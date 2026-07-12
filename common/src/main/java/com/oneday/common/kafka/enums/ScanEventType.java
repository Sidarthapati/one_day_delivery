package com.oneday.common.kafka.enums;

public enum ScanEventType {
    HUB_ORIGIN_IN,          // DA pickup path: HANDED_TO_PICKUP_VAN → AT_ORIGIN_HUB
    SELF_DROP_ACCEPTED,     // self-drop path: AWAITING_SELF_DROP → AT_ORIGIN_HUB
    HUB_ORIGIN_OUT,         // bag dispatched from origin hub → DISPATCHED_TO_AIRPORT (D-007)
    GHA_ACCEPTANCE,
    DEST_SHUTTLE_IN,        // bag loaded on dest-hub shuttle → DISPATCHED_TO_HUB (D-007)
    HUB_DEST_IN,
    LABEL_GENERATED,
    HUB_COLLECT_COMPLETED,  // hub-collect path: AWAITING_HUB_COLLECT → HUB_COLLECTED
    HUB_DEST_OUT,           // HUB_RETURN: DA collected a dest parcel from the hub for last-mile (ledger only)
    DELIVERED,              // DA delivered the box (custody fact only, Option A — DROPPED stays OTP-owned)
}
