package com.oneday.common.port;

import com.oneday.common.domain.MeetingMode;

import java.util.Optional;
import java.util.UUID;

/**
 * Read a city's M6 gate ({@link MeetingMode}) and — for {@code HUB_RETURN} cities — the hub
 * coordinates, without importing the routing module. Implemented in routing over
 * {@code city_fleet_config} + {@code city_logistics_node}; consumed in dispatch (M5) so the
 * delivery-from-hub path can tell whether a city runs van meetings or periodic hub returns.
 * Same cross-module pattern as {@link DaCronSchedulePort} — both sides depend only on {@code common}.
 */
public interface CityMeetingModePort {

    /** Hub rendezvous location for a {@code HUB_RETURN} city. */
    record HubLocation(double lat, double lon) {}

    /** The city's mode. Defaults to {@link MeetingMode#VAN_MEETING} when no fleet config exists. */
    MeetingMode modeFor(UUID cityId);

    /** The city's hub coordinates, if known (present for cities with a HUB logistics node). */
    Optional<HubLocation> hubLocation(UUID cityId);
}
