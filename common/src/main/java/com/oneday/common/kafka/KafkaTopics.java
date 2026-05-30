package com.oneday.common.kafka;

public final class KafkaTopics {

    private KafkaTopics() {}

    // ── Produced by M4 ────────────────────────────────────────────────────
    public static final String SHIPMENTS_EVENTS   = "oneday.shipments.events";

    // ── Produced by M3 (grid) → consumed by M5, M10, M11 ──────────────────
    public static final String GRID_EVENTS        = "oneday.grid.events";       // M3

    // ── Consumed by M4 (one topic per source module) ──────────────────────
    public static final String DA_EVENTS          = "oneday.da.events";         // M5
    public static final String HUB_EVENTS         = "oneday.hub.events";        // M7
    public static final String SCAN_EVENTS        = "oneday.scan.events";       // M8
    public static final String FLIGHT_EVENTS      = "oneday.flight.events";     // M9
    public static final String CRON_EVENTS        = "oneday.cron.events";       // M6
    public static final String EXCEPTIONS_EVENTS  = "oneday.exceptions.events"; // M11

    // ── Command topics (single-consumer, fulfillment-directed) ───────────
    public static final String NOTIFICATIONS_REQUESTED = "oneday.notifications.requested"; // notification service

    // ── Dead-letter queue ─────────────────────────────────────────────────
    public static final String SHIPMENTS_DLQ      = "oneday.shipments.dlq";
}
