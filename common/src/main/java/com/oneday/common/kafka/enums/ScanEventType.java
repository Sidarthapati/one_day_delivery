package com.oneday.common.kafka.enums;

public enum ScanEventType {
    HUB_ORIGIN_IN,          // DA pickup path: HANDED_TO_PICKUP_VAN → AT_ORIGIN_HUB
    SELF_DROP_ACCEPTED,     // self-drop path: AWAITING_SELF_DROP → AT_ORIGIN_HUB
    GHA_ACCEPTANCE,
    HUB_DEST_IN,
    LABEL_GENERATED,
    HUB_COLLECT_COMPLETED   // hub-collect path: AWAITING_HUB_COLLECT → HUB_COLLECTED
}
