package com.oneday.orders.tracking;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.port.LiveDaPositionPort;
import com.oneday.common.port.LivePosition;
import com.oneday.common.port.LiveVanPositionPort;
import com.oneday.orders.config.TrackingProperties;
import com.oneday.orders.domain.Address;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.tracking.CityNodeCatalog.Coord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The heart of live tracking: given a shipment's current M4 state, decide what kind of place it's at
 * and produce a coordinate. Resting states (with the customer, at a hub, at an airport) yield a static
 * pin; the with-a-DA and on-a-van states yield a live dot from {@link LiveDaPositionPort} /
 * {@link LiveVanPositionPort}, degrading to the nearest static node when the GPS fix is missing or
 * stale. The air leg has no live point in v1 — the UI draws the route arc instead.
 *
 * <p>Ports are injected lazily ({@link ObjectProvider}) so the orders module (and any profile without
 * dispatch/routing on the classpath) resolves to static fallbacks rather than failing to wire.</p>
 */
@Component
public class LocationResolver {

    private final CityNodeCatalog cities;
    private final ObjectProvider<LiveDaPositionPort> daPort;
    private final ObjectProvider<LiveVanPositionPort> vanPort;
    private final Duration staleAfter;

    LocationResolver(CityNodeCatalog cities,
                     ObjectProvider<LiveDaPositionPort> daPort,
                     ObjectProvider<LiveVanPositionPort> vanPort,
                     TrackingProperties properties) {
        this.cities = cities;
        this.daPort = daPort;
        this.vanPort = vanPort;
        this.staleAfter = Duration.ofSeconds(properties.getGpsStaleSeconds());
    }

    /** Where the shipment is right now. */
    public ResolvedLocation resolve(Shipment s) {
        LocationKind kind = kindOf(s.getState());
        return switch (kind) {
            case WITH_CUSTOMER -> staticAt(kind, originAddr(s));
            case DELIVERED -> staticAt(kind, deliveredCoord(s));
            case STATIONARY_HUB -> staticAt(kind, hubCoord(s));
            case STATIONARY_AIRPORT -> staticAt(kind, airportCoord(s));
            case ON_FLIGHT -> new ResolvedLocation(kind, null, null, false, true, null, null);
            case MOVING_DA -> moving(kind, s, movingDaFallback(s));
            case MOVING_VAN -> moving(kind, s, movingVanFallback(s));
        };
    }

    // ── state → kind ────────────────────────────────────────────────────────
    static LocationKind kindOf(ShipmentState st) {
        return switch (st) {
            case BOOKED, PICKUP_ASSIGNED, AWAITING_SELF_DROP, PICKUP_FAILED, CANCELLED -> LocationKind.WITH_CUSTOMER;
            case PICKED_UP,
                 DROP_ASSIGNED, HUB_DELIVERY_ASSIGNED, DROP_COLLECTED, COLLECTED_FROM_HUB -> LocationKind.MOVING_DA;
            case HANDED_TO_PICKUP_VAN, RETURNED_TO_HUB,
                 DISPATCHED_TO_AIRPORT, DISPATCHED_TO_HUB, HANDED_TO_DROP_VAN -> LocationKind.MOVING_VAN;
            case AT_ORIGIN_HUB, ORIGIN_HUB_PROCESSING, IN_TAKEOFF_BAG,
                 AT_DEST_HUB, DEST_HUB_PROCESSING, AWAITING_HUB_COLLECT,
                 DELIVERY_FAILED, RTO_INITIATED -> LocationKind.STATIONARY_HUB;
            case AT_AIRPORT, LANDED -> LocationKind.STATIONARY_AIRPORT;
            case DEPARTED, RTO_IN_TRANSIT -> LocationKind.ON_FLIGHT;
            case DROPPED, HUB_COLLECTED, RTO_COMPLETED -> LocationKind.DELIVERED;
        };
    }

    // ── moving legs ─────────────────────────────────────────────────────────
    private ResolvedLocation moving(LocationKind kind, Shipment s, Optional<Coord> fallback) {
        Optional<LivePosition> fix = livePosition(kind, s.getId());
        if (fix.isPresent() && isFresh(fix.get().lastSeenAt())) {
            LivePosition p = fix.get();
            return new ResolvedLocation(kind, p.lat(), p.lon(), true, true, p.lastSeenAt(), p.minutesLate());
        }
        // No fresh fix — show the nearest static node, not a stale dot.
        Coord c = fallback.orElse(null);
        return new ResolvedLocation(kind, latOf(c), lonOf(c), false, false, null, null);
    }

    private Optional<LivePosition> livePosition(LocationKind kind, java.util.UUID shipmentId) {
        if (kind == LocationKind.MOVING_DA) {
            LiveDaPositionPort p = daPort.getIfAvailable();
            return p == null ? Optional.empty() : p.forShipment(shipmentId);
        }
        LiveVanPositionPort p = vanPort.getIfAvailable();
        return p == null ? Optional.empty() : p.forShipment(shipmentId);
    }

    private boolean isFresh(Instant lastSeenAt) {
        return lastSeenAt != null && Duration.between(lastSeenAt, Instant.now()).compareTo(staleAfter) <= 0;
    }

    private Optional<Coord> movingDaFallback(Shipment s) {
        // Pickup leg falls back to the sender's address; delivery leg to the receiver's.
        return s.getState() == ShipmentState.PICKED_UP ? originAddr(s) : destAddr(s);
    }

    private Optional<Coord> movingVanFallback(Shipment s) {
        return switch (s.getState()) {
            case HANDED_TO_PICKUP_VAN, RETURNED_TO_HUB -> cities.hub(s.getOriginCity()).or(() -> originAddr(s));
            case DISPATCHED_TO_AIRPORT -> cities.airport(s.getOriginCity()).or(() -> originAddr(s));
            case DISPATCHED_TO_HUB -> cities.airport(s.getDestCity()).or(() -> destAddr(s));
            case HANDED_TO_DROP_VAN -> cities.hub(s.getDestCity()).or(() -> destAddr(s));
            default -> Optional.empty();
        };
    }

    // ── static nodes ────────────────────────────────────────────────────────
    private Optional<Coord> hubCoord(Shipment s) {
        boolean origin = switch (s.getState()) {
            case AT_ORIGIN_HUB, ORIGIN_HUB_PROCESSING, IN_TAKEOFF_BAG -> true;
            default -> false;   // dest hub for AT_DEST_HUB, DEST_HUB_PROCESSING, AWAITING_HUB_COLLECT, DELIVERY_FAILED, RTO_INITIATED
        };
        return origin ? cities.hub(s.getOriginCity()).or(() -> originAddr(s))
                      : cities.hub(s.getDestCity()).or(() -> destAddr(s));
    }

    private Optional<Coord> airportCoord(Shipment s) {
        // AT_AIRPORT is the origin airport; LANDED is the destination airport.
        return s.getState() == ShipmentState.AT_AIRPORT
                ? cities.airport(s.getOriginCity()).or(() -> originAddr(s))
                : cities.airport(s.getDestCity()).or(() -> destAddr(s));
    }

    private Optional<Coord> deliveredCoord(Shipment s) {
        // RTO ends back with the sender; a normal delivery ends at the receiver.
        return s.getState() == ShipmentState.RTO_COMPLETED ? originAddr(s) : destAddr(s);
    }

    // ── helpers ─────────────────────────────────────────────────────────────
    private static ResolvedLocation staticAt(LocationKind kind, Optional<Coord> c) {
        Coord coord = c.orElse(null);
        return new ResolvedLocation(kind, latOf(coord), lonOf(coord), false, false, null, null);
    }

    private static Optional<Coord> originAddr(Shipment s) {
        return coordOf(s.getOriginAddress());
    }

    private static Optional<Coord> destAddr(Shipment s) {
        return coordOf(s.getDestAddress());
    }

    private static Optional<Coord> coordOf(Address a) {
        if (a == null || a.getLatitude() == null || a.getLongitude() == null) {
            return Optional.empty();
        }
        return Optional.of(new Coord(a.getLatitude(), a.getLongitude()));
    }

    private static Double latOf(Coord c) {
        return c == null ? null : c.lat();
    }

    private static Double lonOf(Coord c) {
        return c == null ? null : c.lon();
    }

    /** The resolved current position. Coordinates may be null when nothing geocoded is available. */
    public record ResolvedLocation(
            LocationKind kind,
            Double lat,
            Double lon,
            boolean live,
            boolean moving,
            Instant lastUpdatedAt,
            Integer minutesLate) {
    }
}
