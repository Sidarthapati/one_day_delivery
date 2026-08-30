package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.PickupType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.B2bAccount;
import com.oneday.orders.domain.ParcelOrder;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.dto.B2bBookingRequest;
import com.oneday.orders.dto.BookingResponse;
import com.oneday.orders.dto.OrderCancellationSummary;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.repository.ParcelOrderRepository;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.B2bBookingService;
import com.oneday.orders.service.CancellationPolicy;
import com.oneday.orders.service.CancellationService;
import com.oneday.orders.service.OrderCapacityService;
import com.oneday.orders.service.OrderRepairService.OrderNotEditableException;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Order repair is B2B + owner-scoped: add/cancel resolve the order to the caller's account (404 on any
 * mismatch), gate edits to still-open orders, run the capacity check before booking, and cancel whole
 * orders partially (eligible children cancelled, the rest reported skipped).
 */
class OrderRepairServiceImplTest {

    private final ParcelOrderRepository orderRepository = mock(ParcelOrderRepository.class);
    private final ShipmentRepository shipmentRepository = mock(ShipmentRepository.class);
    private final B2bAccountRepository accountRepository = mock(B2bAccountRepository.class);
    private final B2bBookingService bookingService = mock(B2bBookingService.class);
    private final CancellationService cancellationService = mock(CancellationService.class);
    private final CancellationPolicy cancellationPolicy = mock(CancellationPolicy.class);
    private final OrderCapacityService capacityService = mock(OrderCapacityService.class);

    private final OrderRepairServiceImpl service = new OrderRepairServiceImpl(
            orderRepository, shipmentRepository, accountRepository, bookingService,
            cancellationService, cancellationPolicy, capacityService);

    private static final String REF = "1DD-ORD-DEL-20260830-00001";
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    private ParcelOrder ownedOrder() {
        ParcelOrder o = mock(ParcelOrder.class);
        when(o.getId()).thenReturn(ORDER_ID);
        when(o.getOrderRef()).thenReturn(REF);
        when(o.getCustomerType()).thenReturn(CustomerType.B2B);
        when(o.getB2bAccountId()).thenReturn(ACCOUNT_ID);
        when(o.getCityId()).thenReturn("DELHI");
        when(orderRepository.findByOrderRef(REF)).thenReturn(Optional.of(o));
        B2bAccount acc = mock(B2bAccount.class);
        when(acc.getId()).thenReturn(ACCOUNT_ID);
        when(accountRepository.findByMemberUserId(USER_ID)).thenReturn(Optional.of(acc));
        return o;
    }

    // Standalone factory — never call this inside another when(...).thenReturn(...) argument.
    private static Shipment ship(ShipmentState state) {
        Shipment s = mock(Shipment.class);
        when(s.getState()).thenReturn(state);
        when(s.getPickupType()).thenReturn(PickupType.DA_PICKUP);
        when(s.getShipmentRef()).thenReturn("1DD-" + state);
        return s;
    }

    private static B2bBookingRequest request() {
        B2bBookingRequest r = new B2bBookingRequest();
        r.setB2bAccountId(UUID.randomUUID());   // deliberately NOT the order's account — must be overridden
        r.setWeightGrams(2_000);
        r.setLengthCm((short) 10);
        r.setWidthCm((short) 10);
        r.setHeightCm((short) 10);
        return r;
    }

    // ── addShipment ─────────────────────────────────────────────────────────

    @Test
    void addShipment_orderNotFound_404() {
        when(orderRepository.findByOrderRef(REF)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.addShipment(REF, request(), "idem", USER_ID.toString()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void addShipment_notOwned_404() {
        ownedOrder();
        B2bAccount other = mock(B2bAccount.class);
        when(other.getId()).thenReturn(UUID.randomUUID());   // caller owns a different account
        when(accountRepository.findByMemberUserId(USER_ID)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.addShipment(REF, request(), "idem", USER_ID.toString()))
                .isInstanceOf(EntityNotFoundException.class);
        verify(bookingService, never()).book(any(), any(), any(), any());
    }

    @Test
    void addShipment_notEditable_409() {
        ownedOrder();
        Shipment atHub = ship(ShipmentState.AT_ORIGIN_HUB);
        when(shipmentRepository.findByOrderIdOrderByCreatedAtAsc(ORDER_ID)).thenReturn(List.of(atHub));
        when(cancellationPolicy.isCancellable(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.addShipment(REF, request(), "idem", USER_ID.toString()))
                .isInstanceOf(OrderNotEditableException.class);
    }

    @Test
    void addShipment_happyPath_overridesAccount_checksCapacity_books() {
        ParcelOrder o = ownedOrder();
        Shipment booked = ship(ShipmentState.BOOKED);
        when(shipmentRepository.findByOrderIdOrderByCreatedAtAsc(ORDER_ID)).thenReturn(List.of(booked));
        when(cancellationPolicy.isCancellable(eq(ShipmentState.BOOKED), any())).thenReturn(true);
        BookingResponse response = mock(BookingResponse.class);
        when(response.getShipmentRef()).thenReturn("1DD-NEW");
        when(bookingService.book(any(), eq("idem"), eq(USER_ID.toString()), eq(ORDER_ID)))
                .thenReturn(response);

        B2bBookingRequest req = request();
        BookingResponse out = service.addShipment(REF, req, "idem", USER_ID.toString());

        assertThat(out).isSameAs(response);
        assertThat(req.getB2bAccountId()).isEqualTo(ACCOUNT_ID);            // account was overridden to the order's
        verify(capacityService).ensureCapacityForAdd(eq(o), eq(2_000));    // capacity gate ran with chargeable weight
        verify(bookingService).book(eq(req), eq("idem"), eq(USER_ID.toString()), eq(ORDER_ID));
    }

    // ── cancelOrder ─────────────────────────────────────────────────────────

    @Test
    void cancelOrder_partial_cancelsEligible_reportsSkipped() {
        ownedOrder();
        Shipment eligible = ship(ShipmentState.BOOKED);
        Shipment pickedUp = ship(ShipmentState.PICKED_UP);
        when(shipmentRepository.findByOrderIdOrderByCreatedAtAsc(ORDER_ID))
                .thenReturn(List.of(eligible, pickedUp));
        when(cancellationPolicy.isCancellable(eq(ShipmentState.BOOKED), any())).thenReturn(true);
        when(cancellationPolicy.isCancellable(eq(ShipmentState.PICKED_UP), any())).thenReturn(false);

        OrderCancellationSummary summary = service.cancelOrder(REF, "changed mind", USER_ID.toString());

        assertThat(summary.cancelled()).containsExactly("1DD-" + ShipmentState.BOOKED);
        assertThat(summary.skipped()).hasSize(1);
        assertThat(summary.skipped().get(0).shipmentRef()).isEqualTo("1DD-" + ShipmentState.PICKED_UP);
        verify(cancellationService).cancel(eq("1DD-" + ShipmentState.BOOKED), eq("changed mind"),
                eq(USER_ID.toString()), eq(true));
        verify(cancellationService, never()).cancel(eq("1DD-" + ShipmentState.PICKED_UP), any(), any(), eq(true));
    }

    @Test
    void cancelOrder_skipsAlreadyCancelled() {
        ownedOrder();
        Shipment cancelled = ship(ShipmentState.CANCELLED);
        when(shipmentRepository.findByOrderIdOrderByCreatedAtAsc(ORDER_ID))
                .thenReturn(List.of(cancelled));

        OrderCancellationSummary summary = service.cancelOrder(REF, null, USER_ID.toString());

        assertThat(summary.cancelled()).isEmpty();
        assertThat(summary.skipped()).isEmpty();
        verify(cancellationService, never()).cancel(any(), any(), any(), anyBoolean());
    }
}
