package com.oneday.common.domain;

/**
 * Per-city gate for M6 (van meeting points). Shared cross-module vocabulary so both routing (which
 * owns the setting on {@code city_fleet_config}) and dispatch (which reads it via
 * {@link com.oneday.common.port.CityMeetingModePort}) speak the same enum without a compile-time
 * dependency between the two modules.
 *
 * <ul>
 *   <li>{@code VAN_MEETING} — full M6: nightly van route plan + meeting-point set-cover; DAs
 *       rendezvous with a van at a grid vertex.</li>
 *   <li>{@code HUB_RETURN} — no M6: the DA periodically returns to the hub to drop collected
 *       pickups and collect their territory's deliveries. The hub is the rendezvous and the
 *       "meeting times" are a fixed periodic cadence.</li>
 * </ul>
 */
public enum MeetingMode {
    VAN_MEETING,
    HUB_RETURN
}
