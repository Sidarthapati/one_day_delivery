package com.oneday.common.domain.enums;

/**
 * SLA colour for a single leg or the whole shipment (M10).
 *
 * <ul>
 *   <li>{@code GREEN}    — on budget.</li>
 *   <li>{@code AMBER}    — a leg has overrun its own budget (eating the engineered buffer), but the
 *       projected end-to-end finish still clears the 16h internal target (M10-D-005).</li>
 *   <li>{@code RED}      — the projected finish crosses the 16h internal target; escalate.</li>
 *   <li>{@code BREACHED} — the internal target has actually passed (or a hard failure occurred).</li>
 *   <li>{@code CLOSED}   — the shipment reached a terminal state; SLA accounting is done.</li>
 * </ul>
 */
public enum SlaState {
    GREEN,
    AMBER,
    RED,
    BREACHED,
    CLOSED
}
