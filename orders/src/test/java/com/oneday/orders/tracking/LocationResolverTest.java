package com.oneday.orders.tracking;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.port.LiveDaPositionPort;
import com.oneday.common.port.LivePosition;
import com.oneday.common.port.LiveVanPositionPort;
import com.oneday.orders.config.TrackingProperties;
import com.oneday.orders.config.TrackingProperties.CityNode;
import com.oneday.orders.domain.Address;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.tracking.LocationResolver.ResolvedLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("LocationResolver")
class LocationResolverTest {

    private final LiveDaPositionPort daPort = mock(LiveDaPositionPort.class);
    private final LiveVanPositionPort vanPort = mock(LiveVanPositionPort.class);

    private LocationResolver resolver(boolean daAvailable, boolean vanAvailable) {
        TrackingProperties props = new TrackingProperties();
        props.setGpsStaleSeconds(180);
        props.setCityNodes(Map.of("DEL", node(28.50, 77.15, 28.5562, 77.10)));
        CityNodeCatalog cities = new CityNodeCatalog(props);
        return new LocationResolver(cities, provider(daAvailable ? daPort : null),
                provider(vanAvailable ? vanPort : null), props);
    }

    @Test
    void bookedIsWithCustomerAtOrigin() {
        ResolvedLocation r = resolver(true, true).resolve(shipment(ShipmentState.BOOKED));
        assertThat(r.kind()).isEqualTo(LocationKind.WITH_CUSTOMER);
        assertThat(r.lat()).isEqualTo(28.60);   // origin address
        assertThat(r.live()).isFalse();
        assertThat(r.moving()).isFalse();
    }

    @Test
    void atOriginHubUsesConfiguredHub() {
        ResolvedLocation r = resolver(true, true).resolve(shipment(ShipmentState.AT_ORIGIN_HUB));
        assertThat(r.kind()).isEqualTo(LocationKind.STATIONARY_HUB);
        assertThat(r.lat()).isEqualTo(28.50);   // DEL hub from config
        assertThat(r.lon()).isEqualTo(77.15);
    }

    @Test
    void atAirportUsesConfiguredAirport() {
        ResolvedLocation r = resolver(true, true).resolve(shipment(ShipmentState.AT_AIRPORT));
        assertThat(r.kind()).isEqualTo(LocationKind.STATIONARY_AIRPORT);
        assertThat(r.lat()).isEqualTo(28.5562);
    }

    @Test
    void departedIsOnFlightWithNoPoint() {
        ResolvedLocation r = resolver(true, true).resolve(shipment(ShipmentState.DEPARTED));
        assertThat(r.kind()).isEqualTo(LocationKind.ON_FLIGHT);
        assertThat(r.lat()).isNull();
        assertThat(r.moving()).isTrue();
        assertThat(r.live()).isFalse();
    }

    @Test
    void droppedIsDeliveredAtDestination() {
        ResolvedLocation r = resolver(true, true).resolve(shipment(ShipmentState.DROPPED));
        assertThat(r.kind()).isEqualTo(LocationKind.DELIVERED);
        assertThat(r.lat()).isEqualTo(19.10);   // dest address
    }

    @Test
    void pickedUpWithFreshDaFixShowsLiveDot() {
        when(daPort.forShipment(any())).thenReturn(Optional.of(
                new LivePosition(28.61, 77.21, Instant.now(), null, null)));
        ResolvedLocation r = resolver(true, true).resolve(shipment(ShipmentState.PICKED_UP));
        assertThat(r.kind()).isEqualTo(LocationKind.MOVING_DA);
        assertThat(r.live()).isTrue();
        assertThat(r.moving()).isTrue();
        assertThat(r.lat()).isEqualTo(28.61);
    }

    @Test
    void pickedUpWithStaleDaFixFallsBackToOrigin() {
        when(daPort.forShipment(any())).thenReturn(Optional.of(
                new LivePosition(28.61, 77.21, Instant.now().minus(30, ChronoUnit.MINUTES), null, null)));
        ResolvedLocation r = resolver(true, true).resolve(shipment(ShipmentState.PICKED_UP));
        assertThat(r.kind()).isEqualTo(LocationKind.MOVING_DA);
        assertThat(r.live()).isFalse();
        assertThat(r.moving()).isFalse();
        assertThat(r.lat()).isEqualTo(28.60);   // origin address fallback
    }

    @Test
    void movingLegWithNoPortFallsBackToStaticNode() {
        // No dispatch port bean available at all — must not blow up, falls back.
        ResolvedLocation r = resolver(false, false).resolve(shipment(ShipmentState.PICKED_UP));
        assertThat(r.kind()).isEqualTo(LocationKind.MOVING_DA);
        assertThat(r.live()).isFalse();
        assertThat(r.lat()).isEqualTo(28.60);
    }

    @Test
    void handedToDropVanWithFreshFixCarriesLateness() {
        when(vanPort.forShipment(any())).thenReturn(Optional.of(
                new LivePosition(19.05, 72.88, Instant.now(), 7, null)));
        ResolvedLocation r = resolver(true, true).resolve(shipment(ShipmentState.HANDED_TO_DROP_VAN));
        assertThat(r.kind()).isEqualTo(LocationKind.MOVING_VAN);
        assertThat(r.live()).isTrue();
        assertThat(r.minutesLate()).isEqualTo(7);
    }

    @Test
    void everyStateResolvesWithoutThrowing() {
        LocationResolver res = resolver(false, false);
        for (ShipmentState st : ShipmentState.values()) {
            assertThat(res.resolve(shipment(st))).as("resolve %s", st).isNotNull();
        }
    }

    // ── fixtures ──────────────────────────────────────────────────────────
    private static Shipment shipment(ShipmentState state) {
        Shipment s = new Shipment();
        s.setState(state);
        s.setOriginCity("DEL");
        s.setDestCity("BOM");
        s.setOriginAddress(address(28.60, 77.20));
        s.setDestAddress(address(19.10, 72.85));
        return s;
    }

    private static Address address(double lat, double lon) {
        Address a = new Address();
        a.setLatitude(lat);
        a.setLongitude(lon);
        return a;
    }

    private static CityNode node(double hubLat, double hubLon, double airLat, double airLon) {
        CityNode n = new CityNode();
        n.setHubLat(hubLat);
        n.setHubLon(hubLon);
        n.setAirportLat(airLat);
        n.setAirportLon(airLon);
        return n;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }
}
