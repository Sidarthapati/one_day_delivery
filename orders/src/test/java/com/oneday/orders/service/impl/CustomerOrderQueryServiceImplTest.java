package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.Address;
import com.oneday.orders.domain.B2bAccount;
import com.oneday.orders.domain.ParcelOrder;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.repository.ParcelOrderRepository;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.CustomerVisibleStateMapper;
import com.oneday.orders.service.port.FlightTrackingPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerOrderQueryServiceImplTest {

    @Mock ShipmentRepository shipmentRepository;
    @Mock ParcelOrderRepository parcelOrderRepository;
    @Mock CustomerVisibleStateMapper stateMapper;
    @Mock FlightTrackingPort flightTrackingPort;
    @Mock B2bAccountRepository b2bAccountRepository;
    @InjectMocks CustomerOrderQueryServiceImpl service;

    private final UUID uid = UUID.randomUUID();
    private final String user = uid.toString();

    private Shipment shipment(UUID owner) {
        Shipment s = new Shipment();
        ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
        s.setShipmentRef("REF");
        s.setBookedByUserId(owner);
        s.setCustomerType(CustomerType.B2C);
        s.setState(ShipmentState.BOOKED);
        s.setTotalPricePaise(4720L);
        return s;
    }

    private ParcelOrder order(UUID owner) {
        ParcelOrder o = new ParcelOrder();
        ReflectionTestUtils.setField(o, "id", UUID.randomUUID());
        o.setOrderRef("ORD-1");
        o.setBookedByUserId(owner);
        o.setCustomerType(CustomerType.B2C);
        o.setParcelCount(1);
        o.setTotalPricePaise(4720L);
        return o;
    }

    @Test
    void myShipments_unauthenticated_empty() {
        assertThat(service.myShipments("not-a-uuid", 10)).isEmpty();
    }

    @Test
    void myShipments_returnsSummaries() {
        lenient().when(stateMapper.labelFor(any())).thenReturn("Booked");
        when(shipmentRepository.findByBookedByUserId(eqUid(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(shipment(uid))));
        assertThat(service.myShipments(user, 10)).hasSize(1);
    }

    @Test
    void myOrders_unauthenticated_empty() {
        assertThat(service.myOrders("x", 5)).isEmpty();
    }

    @Test
    void myOrders_groupsChildStates() {
        ParcelOrder o = order(uid);
        when(parcelOrderRepository.findByBookedByUserId(eqUid(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(o)));
        ShipmentRepository.OrderChildState proj = org.mockito.Mockito.mock(ShipmentRepository.OrderChildState.class);
        when(proj.getOrderId()).thenReturn(o.getId());
        when(proj.getState()).thenReturn(ShipmentState.BOOKED);
        when(shipmentRepository.findChildStatesByOrderIds(any())).thenReturn(List.of(proj));
        assertThat(service.myOrders(user, 5)).hasSize(1);
    }

    @Test
    void myOrderDetail_nullId_empty() {
        assertThat(service.myOrderDetail("x", "ORD-1")).isEmpty();
    }

    @Test
    void myOrderDetail_notOwned_empty() {
        ParcelOrder o = order(UUID.randomUUID()); // owned by someone else
        when(parcelOrderRepository.findByOrderRef("ORD-1")).thenReturn(Optional.of(o));
        assertThat(service.myOrderDetail(user, "ORD-1")).isEmpty();
    }

    @Test
    void myOrderDetail_owned_returnsDetailWithChildren() {
        ParcelOrder o = order(uid);
        when(parcelOrderRepository.findByOrderRef("ORD-1")).thenReturn(Optional.of(o));
        when(shipmentRepository.findByOrderIdOrderByCreatedAtAsc(o.getId()))
                .thenReturn(List.of(shipment(uid)));
        lenient().when(stateMapper.labelFor(any())).thenReturn("Booked");
        assertThat(service.myOrderDetail(user, "ORD-1")).isPresent();
    }

    @Test
    void myShipmentDetail_nullId_empty() {
        assertThat(service.myShipmentDetail("x", "REF")).isEmpty();
    }

    @Test
    void myShipmentDetail_owned_withFlightPosition() {
        Shipment s = shipment(uid);
        s.setOriginAddress(addr());
        s.setDestAddress(addr());
        when(shipmentRepository.findByShipmentRef("REF")).thenReturn(Optional.of(s));
        when(stateMapper.labelFor(any())).thenReturn("Booked");
        when(flightTrackingPort.currentPosition(s.getId())).thenReturn(
                Optional.of(new FlightTrackingPort.LivePosition(19.0, 72.0, Instant.now(), "IN_AIR")));
        assertThat(service.myShipmentDetail(user, "REF")).isPresent();
    }

    @Test
    void myShipmentDetail_owned_noPosition_nullAddresses() {
        Shipment s = shipment(uid); // addresses null
        when(shipmentRepository.findByShipmentRef("REF")).thenReturn(Optional.of(s));
        when(stateMapper.labelFor(any())).thenReturn("Booked");
        when(flightTrackingPort.currentPosition(s.getId())).thenReturn(Optional.empty());
        assertThat(service.myShipmentDetail(user, "REF")).isPresent();
    }

    @Test
    void shipmentLabel_nullId_empty() {
        assertThat(service.shipmentLabel("x", "REF")).isEmpty();
    }

    @Test
    void shipmentLabel_b2b_withBrand_richAddress() {
        Shipment s = shipment(uid);
        s.setCustomerType(CustomerType.B2B);
        s.setB2bAccountId(UUID.randomUUID());
        Address o = new Address();
        o.setHouseFloor("4th floor");
        o.setBuildingStreet("Tower A");
        o.setCity("DEL");
        s.setOriginAddress(o);           // house/building parts → appendPart separator branch
        s.setDestAddress(lineOnlyAddr()); // only line1/line2 → fallback branch
        when(shipmentRepository.findByShipmentRef("REF")).thenReturn(Optional.of(s));
        B2bAccount acct = new B2bAccount();
        acct.setBrandName("AcmeBrand");
        acct.setGstin("G1");
        when(b2bAccountRepository.findById(s.getB2bAccountId())).thenReturn(Optional.of(acct));
        assertThat(service.shipmentLabel(user, "REF")).isPresent();
    }

    @Test
    void shipmentLabel_b2c_nullSeller_nullAddresses() {
        Shipment s = shipment(uid); // B2C, addresses null → formatAddress null branch
        when(shipmentRepository.findByShipmentRef("REF")).thenReturn(Optional.of(s));
        assertThat(service.shipmentLabel(user, "REF")).isPresent();
    }

    private static Address addr() {
        Address a = new Address();
        a.setLine1("Street 1");
        a.setLatitude(28.5);
        a.setLongitude(77.1);
        a.setCity("DEL");
        return a;
    }

    private static Address lineOnlyAddr() {
        Address a = new Address();
        a.setLine1("Flat 2B");
        a.setLine2("Bandra");
        return a;
    }

    private UUID eqUid() {
        return org.mockito.ArgumentMatchers.eq(uid);
    }
}
