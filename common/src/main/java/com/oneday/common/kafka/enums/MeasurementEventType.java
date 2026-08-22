package com.oneday.common.kafka.enums;

/** Event types for parcel-dimension measurements (produced by M4 on the shipments stream). */
public enum MeasurementEventType {
    /** A measurement was recorded (any outcome). */
    MEASUREMENT_RECORDED,
    /** A measurement showed the parcel materially exceeds the declared dimensions. */
    DIMENSION_DISCREPANCY_FLAGGED
}
