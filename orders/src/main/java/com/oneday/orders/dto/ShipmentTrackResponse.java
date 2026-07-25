package com.oneday.orders.dto;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.tracking.LocationKind;

import java.time.Instant;
import java.util.List;

/**
 * Live-tracking view of one of the caller's shipments ({@code GET /api/v1/shipments/mine/{ref}/track}).
 * Combines the current state, a resolved current position (static node or live dot), the route to
 * draw, and the milestone timeline. Field names serialise to snake_case via the global Jackson config.
 */
public record ShipmentTrackResponse(
        String shipmentRef,
        ShipmentState state,
        String stateLabel,
        Location location,
        Route route,
        List<Milestone> milestones,
        Eta eta) {

    /**
     * The current position. {@code lat}/{@code lon} are null for the air leg (draw the arc) or when no
     * coordinate is available. {@code live} = a fresh GPS fix; {@code moving} = the parcel is in motion.
     */
    public record Location(
            LocationKind kind,
            Double lat,
            Double lon,
            boolean live,
            boolean moving,
            String label,
            Instant lastUpdatedAt,
            Integer minutesLate) {}

    /** Endpoints + the fixed waypoints (hubs/airports) between them, for drawing the journey line. */
    public record Route(
            Double originLat,
            Double originLon,
            String originCity,
            Double destLat,
            Double destLon,
            String destCity,
            List<Waypoint> waypoints) {}

    public record Waypoint(double lat, double lon, String label) {}

    /** One timeline row. {@code occurredAt} is null for a step not yet reached. */
    public record Milestone(String label, Instant occurredAt, boolean done) {}

    public record Eta(Instant promised, Instant updated) {}
}
