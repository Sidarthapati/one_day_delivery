package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.port.CourierOnShipmentPort;
import com.oneday.orders.domain.Address;
import com.oneday.orders.domain.B2bAccount;
import com.oneday.orders.domain.Shipment;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.CustomerVisibleStateMapper;
import com.oneday.orders.tracking.CityNodeCatalog;
import com.oneday.orders.tracking.LocationResolver;
import com.oneday.orders.tracking.LocationKind;
import com.oneday.orders.tracking.LocationResolver.ResolvedLocation;
import com.oneday.orders.tracking.MilestoneBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrackingServiceImplTest {

    @Mock ShipmentRepository shipmentRepository;
    @Mock B2bAccountRepository b2bAccountRepository;
    @Mock CustomerVisibleStateMapper stateMapper;
    @Mock LocationResolver locationResolver;
    @Mock MilestoneBuilder milestoneBuilder;
    @Mock CityNodeCatalog cities;
    @Mock ObjectProvider<CourierOnShipmentPort> courierPort;
    @InjectMocks TrackingServiceImpl service;

    private final UUID uid = UUID.randomUUID();
    private final String user = uid.toString();

    @BeforeEach
    void stubCommon() {
        lenient().when(stateMapper.labelFor(any())).thenReturn("In transit");
        lenient().when(locationResolver.resolve(any())).thenReturn(
                new ResolvedLocation(LocationKind.STATIONARY_HUB, 1.0, 2.0, true, false, null, null));
        lenient().when(milestoneBuilder.build(any())).thenReturn(List.of());
        lenient().when(cities.hub(anyString())).thenReturn(Optional.of(new CityNodeCatalog.Coord(1, 2)));
        lenient().when(cities.airport(anyString())).thenReturn(Optional.of(new CityNodeCatalog.Coord(3, 4)));
    }

    private Shipment owned(DeliveryType type, boolean withAddresses) {
        Shipment s = new Shipment();
        ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
        s.setShipmentRef("REF");
        s.setBookedByUserId(uid);
        s.setState(ShipmentState.AT_ORIGIN_HUB);
        s.setDeliveryType(type);
        s.setOriginCity("DEL");
        s.setDestCity("BOM");
        if (withAddresses) {
            s.setOriginAddress(addr());
            s.setDestAddress(addr());
        }
        return s;
    }

    @Test
    void track_nullId_empty() {
        assertThat(service.track("x", "REF")).isEmpty();
    }

    @Test
    void track_courierPortAbsent_intercity() {
        when(shipmentRepository.findByShipmentRef("REF")).thenReturn(Optional.of(owned(DeliveryType.INTERCITY, true)));
        when(courierPort.getIfAvailable()).thenReturn(null);
        assertThat(service.track(user, "REF")).isPresent();
    }

    @Test
    void track_courierPickupRole_sameCity() {
        when(shipmentRepository.findByShipmentRef("REF")).thenReturn(Optional.of(owned(DeliveryType.SAME_CITY, false)));
        CourierOnShipmentPort port = org.mockito.Mockito.mock(CourierOnShipmentPort.class);
        when(courierPort.getIfAvailable()).thenReturn(port);
        when(port.forShipment(any())).thenReturn(Optional.of(
                new CourierOnShipmentPort.Courier("Ravi", "+91", CourierOnShipmentPort.Role.PICKUP)));
        assertThat(service.track(user, "REF")).isPresent();
    }

    @Test
    void track_courierDeliveryRole_andEmpty() {
        when(shipmentRepository.findByShipmentRef("REF")).thenReturn(Optional.of(owned(DeliveryType.INTERCITY, true)));
        CourierOnShipmentPort port = org.mockito.Mockito.mock(CourierOnShipmentPort.class);
        when(courierPort.getIfAvailable()).thenReturn(port);
        when(port.forShipment(any())).thenReturn(Optional.of(
                new CourierOnShipmentPort.Courier("Sita", "+91", CourierOnShipmentPort.Role.DELIVERY)));
        assertThat(service.track(user, "REF")).isPresent();
    }

    @Test
    void track_courierEmpty_returnsNullCourier() {
        when(shipmentRepository.findByShipmentRef("REF")).thenReturn(Optional.of(owned(DeliveryType.INTERCITY, false)));
        CourierOnShipmentPort port = org.mockito.Mockito.mock(CourierOnShipmentPort.class);
        when(courierPort.getIfAvailable()).thenReturn(port);
        when(port.forShipment(any())).thenReturn(Optional.empty());
        assertThat(service.track(user, "REF")).isPresent();
    }

    @Test
    void trackByToken_blank_empty() {
        assertThat(service.trackByToken("  ")).isEmpty();
        assertThat(service.trackByToken(null)).isEmpty();
    }

    @Test
    void trackByToken_noBrand_whenNoAccount() {
        Shipment s = owned(DeliveryType.INTERCITY, true); // b2bAccountId null
        when(shipmentRepository.findByTrackToken("tok")).thenReturn(Optional.of(s));
        assertThat(service.trackByToken("tok")).isPresent();
    }

    @Test
    void trackByToken_brandFromAccount() {
        Shipment s = owned(DeliveryType.SAME_CITY, false);
        s.setB2bAccountId(UUID.randomUUID());
        when(shipmentRepository.findByTrackToken("tok")).thenReturn(Optional.of(s));
        when(b2bAccountRepository.findById(s.getB2bAccountId())).thenReturn(Optional.of(new B2bAccount()));
        assertThat(service.trackByToken("tok")).isPresent();
    }

    @Test
    void trackByToken_accountMissing_emptyBranding() {
        Shipment s = owned(DeliveryType.INTERCITY, false);
        s.setB2bAccountId(UUID.randomUUID());
        when(shipmentRepository.findByTrackToken("tok")).thenReturn(Optional.of(s));
        when(b2bAccountRepository.findById(s.getB2bAccountId())).thenReturn(Optional.empty());
        assertThat(service.trackByToken("tok")).isPresent();
    }

    private static Address addr() {
        Address a = new Address();
        a.setLatitude(28.5);
        a.setLongitude(77.1);
        return a;
    }
}
