package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.PaymentMode;
import com.oneday.common.domain.enums.PickupType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.B2bAccount;
import com.oneday.orders.domain.PaymentTransaction;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.domain.enums.PaymentStatus;
import com.oneday.orders.domain.enums.RefundStatus;
import com.oneday.orders.dto.CancellationResponse;
import com.oneday.orders.events.ShipmentCancelled;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.repository.PaymentTransactionRepository;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.B2bBookingService;
import com.oneday.orders.service.CancellationPolicy;
import com.oneday.orders.service.CancellationService;
import com.oneday.orders.service.PaymentPort;
import com.oneday.orders.service.ShipmentStateMachine;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CancellationServiceImplTest {

    private static final String USER = "00000000-0000-0000-0000-000000000001";
    private static final String REF = "1DD-BLR-20260606-00001";

    @Mock private ShipmentRepository shipmentRepository;
    @Mock private PaymentTransactionRepository paymentTransactionRepository;
    @Mock private B2bAccountRepository b2bAccountRepository;
    @Mock private CancellationPolicy cancellationPolicy;
    @Mock private ShipmentStateMachine stateMachine;
    @Mock private PaymentPort paymentPort;
    @Mock private ApplicationEventPublisher events;

    private CancellationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CancellationServiceImpl(shipmentRepository, paymentTransactionRepository,
                b2bAccountRepository, cancellationPolicy, stateMachine, paymentPort, events);
        lenient().when(cancellationPolicy.isCancellable(any(), any())).thenReturn(true);
    }

    // ── PREPAID retail ────────────────────────────────────────────────────────

    @Test
    void prepaidCancel_initiatesRazorpayRefund_andTransitions() {
        Shipment s = shipment(CustomerType.B2C, PaymentMode.PREPAID, PickupType.DA_PICKUP,
                ShipmentState.BOOKED, 53100L, null);
        when(shipmentRepository.findByShipmentRef(REF)).thenReturn(Optional.of(s));
        PaymentTransaction tx = capturedTx(s.getId(), "pay_ABC");
        when(paymentTransactionRepository.findByShipmentId(s.getId())).thenReturn(List.of(tx));
        when(paymentPort.initiateRefund("pay_ABC", 53100L)).thenReturn("rfnd_XYZ");

        CancellationResponse resp = service.cancel(REF, "changed mind", USER, false);

        assertThat(resp.state()).isEqualTo(ShipmentState.CANCELLED);
        assertThat(resp.refund()).isNotNull();
        assertThat(resp.refund().status()).isEqualTo("REFUND_INITIATED");
        assertThat(resp.refund().refundAmountPaise()).isEqualTo(53100L);
        assertThat(resp.refund().razorpayRefundId()).isEqualTo("rfnd_XYZ");
        assertThat(tx.getStatus()).isEqualTo(PaymentStatus.REFUND_INITIATED);
        assertThat(tx.getRefundStatus()).isEqualTo(RefundStatus.PENDING);

        verify(stateMachine).transition(eq(s.getId()), eq(ShipmentState.CANCELLED), any());
        assertThat(s.getCancelledAt()).isNotNull();
        assertThat(s.getCancellationReason()).isEqualTo("changed mind");

        ShipmentCancelled emitted = captureEvent();
        assertThat(emitted.refundInitiated()).isTrue();
        assertThat(emitted.refundAmountPaise()).isEqualTo(53100L);
        assertThat(emitted.cancelledAtState()).isEqualTo(ShipmentState.BOOKED);
    }

    @Test
    void prepaidRefundFails_stillCancels_flagsFailed() {
        Shipment s = shipment(CustomerType.B2C, PaymentMode.PREPAID, PickupType.DA_PICKUP,
                ShipmentState.PICKED_UP, 53100L, null);
        when(shipmentRepository.findByShipmentRef(REF)).thenReturn(Optional.of(s));
        PaymentTransaction tx = capturedTx(s.getId(), "pay_ABC");
        when(paymentTransactionRepository.findByShipmentId(s.getId())).thenReturn(List.of(tx));
        when(paymentPort.initiateRefund("pay_ABC", 53100L))
                .thenThrow(new PaymentPort.PaymentRefundException("razorpay down"));

        CancellationResponse resp = service.cancel(REF, null, USER, false);

        assertThat(resp.state()).isEqualTo(ShipmentState.CANCELLED);
        assertThat(resp.refund().status()).isEqualTo("REFUND_FAILED");
        assertThat(tx.getRefundStatus()).isEqualTo(RefundStatus.FAILED);
        verify(stateMachine).transition(eq(s.getId()), eq(ShipmentState.CANCELLED), any());
        assertThat(captureEvent().refundInitiated()).isFalse();
    }

    // ── COD retail ──────────────────────────────────────────────────────────

    @Test
    void codCancel_noRefund() {
        Shipment s = shipment(CustomerType.C2C, PaymentMode.COD, PickupType.DA_PICKUP,
                ShipmentState.PICKUP_ASSIGNED, 53100L, null);
        when(shipmentRepository.findByShipmentRef(REF)).thenReturn(Optional.of(s));

        CancellationResponse resp = service.cancel(REF, null, USER, false);

        assertThat(resp.state()).isEqualTo(ShipmentState.CANCELLED);
        assertThat(resp.refund()).isNull();
        verify(paymentPort, never()).initiateRefund(any(), anyLong());
        assertThat(captureEvent().refundInitiated()).isFalse();
    }

    // ── B2B credit reversal ───────────────────────────────────────────────────

    @Test
    void b2bCancel_decrementsOutstanding() {
        UUID accountId = UUID.randomUUID();
        Shipment s = shipment(CustomerType.B2B, null, PickupType.DA_PICKUP,
                ShipmentState.BOOKED, 20000L, accountId);
        when(shipmentRepository.findByShipmentRef(REF)).thenReturn(Optional.of(s));
        B2bAccount account = account(accountId, 50000L, UUID.fromString(USER));
        when(b2bAccountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));

        CancellationResponse resp = service.cancel(REF, "duplicate PO", USER, true);

        assertThat(resp.state()).isEqualTo(ShipmentState.CANCELLED);
        assertThat(resp.refund()).isNull();
        assertThat(account.getOutstandingBalancePaise()).isEqualTo(30000L);
        verify(paymentPort, never()).initiateRefund(any(), anyLong());
    }

    @Test
    void b2bCancel_notOwner_throwsAccountAccess() {
        UUID accountId = UUID.randomUUID();
        Shipment s = shipment(CustomerType.B2B, null, PickupType.DA_PICKUP,
                ShipmentState.BOOKED, 20000L, accountId);
        when(shipmentRepository.findByShipmentRef(REF)).thenReturn(Optional.of(s));
        B2bAccount account = account(accountId, 50000L, UUID.randomUUID()); // different owner
        when(b2bAccountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.cancel(REF, null, USER, true))
                .isInstanceOf(B2bBookingService.AccountAccessException.class);
        verify(stateMachine, never()).transition(any(), any(), any());
    }

    // ── Guards ────────────────────────────────────────────────────────────────

    @Test
    void pastCutoff_throwsCancellationNotAllowed() {
        Shipment s = shipment(CustomerType.B2C, PaymentMode.PREPAID, PickupType.DA_PICKUP,
                ShipmentState.AT_ORIGIN_HUB, 53100L, null);
        when(shipmentRepository.findByShipmentRef(REF)).thenReturn(Optional.of(s));
        when(cancellationPolicy.isCancellable(ShipmentState.AT_ORIGIN_HUB, PickupType.DA_PICKUP))
                .thenReturn(false);

        assertThatThrownBy(() -> service.cancel(REF, null, USER, false))
                .isInstanceOf(CancellationService.CancellationNotAllowedException.class);
        verify(stateMachine, never()).transition(any(), any(), any());
    }

    @Test
    void missingShipment_throwsNotFound() {
        when(shipmentRepository.findByShipmentRef(REF)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.cancel(REF, null, USER, false))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void b2cCallerOnB2bShipment_throwsNotFound() {
        Shipment s = shipment(CustomerType.B2B, null, PickupType.DA_PICKUP,
                ShipmentState.BOOKED, 20000L, UUID.randomUUID());
        when(shipmentRepository.findByShipmentRef(REF)).thenReturn(Optional.of(s));

        assertThatThrownBy(() -> service.cancel(REF, null, USER, false)) // b2bLane=false
                .isInstanceOf(EntityNotFoundException.class);
        verify(stateMachine, never()).transition(any(), any(), any());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Shipment shipment(CustomerType type, PaymentMode mode, PickupType pickupType,
                              ShipmentState state, long totalPaise, UUID b2bAccountId) {
        Shipment s = new Shipment();
        ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
        s.setShipmentRef(REF);
        s.setCustomerType(type);
        s.setPaymentMode(mode);
        s.setPickupType(pickupType);
        s.setState(state);
        s.setTotalPricePaise(totalPaise);
        s.setB2bAccountId(b2bAccountId);
        return s;
    }

    private PaymentTransaction capturedTx(UUID shipmentId, String paymentId) {
        PaymentTransaction tx = new PaymentTransaction();
        tx.setShipmentId(shipmentId);
        tx.setStatus(PaymentStatus.CAPTURED);
        tx.setRazorpayPaymentId(paymentId);
        return tx;
    }

    private B2bAccount account(UUID id, long outstanding, UUID owner) {
        B2bAccount a = new B2bAccount();
        ReflectionTestUtils.setField(a, "id", id);
        a.setOutstandingBalancePaise(outstanding);
        a.setOwnerUserId(owner);
        return a;
    }

    private ShipmentCancelled captureEvent() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(ShipmentCancelled.class);
        return (ShipmentCancelled) captor.getValue();
    }
}
