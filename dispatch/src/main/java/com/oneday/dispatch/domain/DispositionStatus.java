package com.oneday.dispatch.domain;

/**
 * Lifecycle of a {@code da_disposition}.
 * <ul>
 *   <li>{@code PENDING} — raised, awaiting manager approval (AUXILIARY / DAY_OFF only).</li>
 *   <li>{@code ACTIVE} — in effect; the DA is {@code ON_BREAK} and his territory is held.</li>
 *   <li>{@code COMPLETED} — the DA returned (or it was closed cleanly).</li>
 *   <li>{@code OVERSTAYED} — past its window + grace; escalated to the station manager.</li>
 *   <li>{@code REJECTED} — a manager declined the request.</li>
 *   <li>{@code CANCELLED} — withdrawn / superseded.</li>
 * </ul>
 */
public enum DispositionStatus {
    PENDING,
    ACTIVE,
    COMPLETED,
    OVERSTAYED,
    REJECTED,
    CANCELLED
}
