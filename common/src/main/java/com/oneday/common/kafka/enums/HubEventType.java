package com.oneday.common.kafka.enums;

/**
 * Event types M7 (hub) publishes on {@code oneday.hub.events} (M7 design §14.1).
 * The first four predate M7's build; the rest land with PR #1/#2/#3.
 */
public enum HubEventType {

    // ── Pre-existing ──────────────────────────────────────────────────────
    STAND_ASSIGNED,
    BAG_CREATED,
    SAMECITY_OUTBOUND,
    DEST_SORT_COMPLETE,

    /**
     * @deprecated M7-D-004 — M7 stages, M6 loads. {@code HANDED_TO_DROP_VAN} is driven by M6's
     * {@code VAN_LOAD} scan, not an M7 event. Kept for back-compat; never emitted by M7 v1.
     */
    @Deprecated
    DROP_VAN_HANDOFF,

    // ── Added by M7 (§14.1) ───────────────────────────────────────────────
    PARCEL_SORTED_FOR_DELIVERY,   // per-parcel → M6 binds to a loop (M7-D-002); emitted in PR #2
    BAG_SEALED,                   // bag frozen + manifest generated → M9, M10
    MANIFEST_GENERATED,           // system manifest ready → M9 handover
    BAG_RESCHEDULED,              // low-weight/forced move to a later flight (§9, §10); PR #3
    HUB_OVERLOAD_ALERT,           // wave/stand overload → M10, station mgr (M7-D-007); PR #3
    HUB_DISCREPANCY               // dock reconciliation mismatch → M11, M10
}
