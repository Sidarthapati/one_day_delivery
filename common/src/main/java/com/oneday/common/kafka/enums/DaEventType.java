package com.oneday.common.kafka.enums;

public enum DaEventType {
    // M4 consumer: BOOKED → PICKUP_ASSIGNED (side-effect: OTP generated and sent to customer)
    PICKUP_ASSIGNED,

    // NOT consumed by M4 for state transitions — PICKED_UP is triggered by OTP verify HTTP endpoint.
    // Produced by M5 for other consumers (M10 SLA).
    PICKUP_COMPLETED,

    // M4 consumer: PICKUP_ASSIGNED → PICKUP_FAILED
    PICKUP_FAILED,

    // M4 consumer: PICKED_UP → HANDED_TO_PICKUP_VAN  [QR scan — DA scans parcel in DA app]
    VAN_HANDOFF_COMPLETED,

    // M4 consumer: HANDED_TO_DROP_VAN → DROP_ASSIGNED
    DROP_ASSIGNED,

    // M4 consumer: DROP_ASSIGNED → DROP_COLLECTED  [QR scan — DA scans parcel in DA app when collecting from van]
    DROP_COLLECTED,

    // M4 consumer: DROP_COLLECTED → DROPPED  [verification: see OD-8]
    DROP_COMPLETED,

    // M4 consumer: DROP_COLLECTED → DELIVERY_FAILED
    DROP_FAILED,

    // ── M5-internal events (NOT consumed by M4 for state transitions) ──────────
    // Queue order changed (insertion / cancel / reschedule); keyed by daId. → ops/UI
    QUEUE_REORDERED,

    // DA missed the GPS-heartbeat threshold and was marked ABSENT; keyed by daId. → M10, M11
    DA_ABSENT,

    // A parcel could not make its DA's cron meeting in time. → M10, M11
    CRON_MISSED,

    // COD cash collected at delivery; emitted alongside DROP_COMPLETED. → finance / M10
    COD_COLLECTED,

    // QUEUED tasks deferred at shift end (carried to next day / RTO path). → M11
    TASK_DEFERRED_SHIFT_ENDED
}
