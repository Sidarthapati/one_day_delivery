package com.oneday.common.kafka.enums;

public enum GridEventType {
    // No DA assigned to an active hex for today → M5 (emergency sourcing), M11 (call-centre ticket)
    NO_DA_ALERT,

    // Hex adjusted load score sustained above WARNING/CRITICAL threshold → M5 (rebalancing), M10 (SLA)
    TILE_OVERLOAD_ALERT,

    // Active plan changed via intraday approval → M5 (patch routing cache for affected DAs).
    // Reserved: not yet emitted (ProposalServiceImpl.approve() publish is a separate task).
    ASSIGNMENT_UPDATED
}
