package com.oneday.common.kafka.enums;

/**
 * Discriminator for events M6 (routing) publishes on {@code oneday.cron.events}
 * ({@link com.oneday.common.kafka.KafkaTopics#CRON_EVENTS}). See M6 design §17.1.
 *
 * <p>Custody <em>scans</em> (VAN_LOAD / VAN_TO_DA / DA_TO_VAN / VAN_UNLOAD) are NOT here —
 * they are written to M8's append-only scan ledger, not duplicated on this topic.
 * Raw GPS pings are not events at all (M6-D-012).</p>
 */
public enum CronEventType {

    // ── plan-time ────────────────────────────────────────────────────────
    DA_CRON_SCHEDULED,     // a DA's meeting vertex + the day's meeting times      → M5
    SHUTTLE_SCHEDULED,     // hub↔airport shuttle timetable                        → M9, M10
    ROUTE_PLAN_PUBLISHED,  // a city's van plan is approved & active               → M10, van app
    ROUTE_CHANGED,         // an intraday override took effect                     → M5, M10, station mgr

    // ── run-time: tracking ───────────────────────────────────────────────
    VAN_ARRIVED,           // van reached a meeting vertex                         → M10, ops
    VAN_RUNNING_LATE,      // live ETA slipped past threshold                      → M5, M10, ops

    // ── run-time: custody & failures ─────────────────────────────────────
    HANDOFF_COMPLETED,     // a stop's per-DA handoff reconciled OK                → M4, M10
    HANDOFF_DISCREPANCY,   // missing/extra/rejected parcel at a stop              → M11, M10
    LOOP_OVERFLOW,         // a parcel can't fit a feasible loop before deadline   → M10, station mgr
    VAN_BREAKDOWN          // van disabled mid-loop                                → M10, station mgr, M11
}
