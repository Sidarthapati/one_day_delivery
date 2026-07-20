package com.oneday.common.kafka;

/**
 * Logical event-stream names. Each constant is the name of a RabbitMQ <b>topic exchange</b>
 * (one exchange per producing module); routing key = the event-type enum value. Consuming
 * modules declare their own queue bound to the exchange (see {@code *MessagingTopology} configs
 * and {@code docs/EVENT-BUS-ARCHITECTURE.md}).
 *
 * <p>Formerly {@code KafkaTopics}; values are unchanged so the broker/exchange names are stable.</p>
 */
public final class EventStreams {

    private EventStreams() {}

    // ── Produced by M4 ────────────────────────────────────────────────────
    public static final String SHIPMENTS_EVENTS   = "oneday.shipments.events";

    // ── Produced by M3 (grid) → consumed by M5, M10, M11 ──────────────────
    public static final String GRID_EVENTS        = "oneday.grid.events";       // M3

    // ── One exchange per source module ────────────────────────────────────
    public static final String DA_EVENTS          = "oneday.da.events";         // M5
    public static final String HUB_EVENTS         = "oneday.hub.events";        // M7
    public static final String SCAN_EVENTS        = "oneday.scan.events";       // M8
    public static final String FLIGHT_EVENTS      = "oneday.flight.events";     // M9
    public static final String CRON_EVENTS        = "oneday.cron.events";       // M6
    public static final String EXCEPTIONS_EVENTS  = "oneday.exceptions.events"; // M11

    // ── Produced by M4 (Orders) → consumed by M3 (grid) ───────────────────
    public static final String TILE_QUEUE_DEPTH   = "orders.tile_queue_depth";

    // ── Produced by M10 (SLA) → consumed by M11, ops/notification service ─
    public static final String SLA_EVENTS         = "oneday.sla.events";        // M10

    // ── Notification fan-out (consumed by the notification service) ──────
    public static final String NOTIFICATIONS_EVENTS = "oneday.notifications.events";
}
