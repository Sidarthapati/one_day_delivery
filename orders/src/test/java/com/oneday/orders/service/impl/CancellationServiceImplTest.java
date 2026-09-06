package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.ReturnReason;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.port.HubRecallPort;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.dto.CancellationResponse;
import com.oneday.orders.dto.CancellationResponse.Disposition;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.repository.PaymentTransactionRepository;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.CancellationPolicy;
import com.oneday.orders.service.CancellationService.CancellationNotAllowedException;
import com.oneday.orders.service.OrderService;
import com.oneday.orders.service.PaymentPort;
import com.oneday.orders.service.ReturnService;
import com.oneday.orders.service.ReturnService.ReturnLane;
import com.oneday.orders.service.ShipmentStateMachine;
import com.oneday.orders.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

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

/** Router coverage: an in-custody cancel becomes an RTO on the right lane / timing (feature iii). */
class CancellationServiceImplTest {

    private ShipmentRepository shipmentRepo;
    private CancellationPolicy policy;
    private ReturnService returnService;
    private HubRecallPort hubRecall;
    private CancellationServiceImpl service;

    private final UUID shipmentId = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        shipmentRepo = mock(ShipmentRepository.class);
        policy = mock(CancellationPolicy.class);
        returnService = mock(ReturnService.class);
        hubRecall = mock(HubRecallPort.class);
        ObjectProvider<HubRecallPort> hubRecallProvider = mock(ObjectProvider.class);
        when(hubRecallProvider.getIfAvailable()).thenReturn(hubRecall);

        service = new CancellationServiceImpl(
                shipmentRepo, mock(PaymentTransactionRepository.class), mock(B2bAccountRepository.class),
                policy, mock(ShipmentStateMachine.class), mock(PaymentPort.class), mock(WalletService.class),
                mock(OrderService.class), mock(ApplicationEventPublisher.class), returnService, hubRecallProvider);

        when(shipmentRepo.save(any(Shipment.class))).thenAnswer(inv -> inv.getArgument(0));
        // Everything routed here is past the refund cutoff → the router (not the refund path) engages.
        when(policy.isCancellable(any(), any())).thenReturn(false);
        when(returnService.initiateReturn(any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new ReturnService.ReturnResult(UUID.randomUUID(), "REF_R", shipmentId));
    }

    private Shipment shipmentIn(ShipmentState state) {
        Shipment s = new Shipment();
        ReflectionTestUtils.setField(s, "id", shipmentId);
        s.setShipmentRef("REF");
        s.setCustomerType(CustomerType.B2C);
        s.setState(state);
        when(shipmentRepo.findByShipmentRef("REF")).thenReturn(Optional.of(s));
        return s;
    }

    @Test
    void preHubInHandSchedulesTheReturn() {
        Shipment s = shipmentIn(ShipmentState.PICKED_UP);

        CancellationResponse r = service.initiateRtoAsAdmin("REF", "merchant cancelled", "admin-1");

        assertThat(r.disposition()).isEqualTo(Disposition.RETURN_SCHEDULED);
        assertThat(s.getRtoRequestedAt()).isNotNull();
        assertThat(s.getRtoResolvedAt()).isNull();
        verify(returnService, never()).initiateReturn(any(), any(), any(), anyBoolean(), any()); // deferred, fires at the hub
    }

    @Test
    void atDestHubReturnsReverseLaneNow() {
        shipmentIn(ShipmentState.AT_DEST_HUB);

        CancellationResponse r = service.initiateRtoAsAdmin("REF", "cancel", "admin-1");

        assertThat(r.disposition()).isEqualTo(Disposition.RETURN_INITIATED);
        assertThat(r.returnChildRef()).isEqualTo("REF_R");
        verify(returnService).initiateReturn(eq(shipmentId), eq(ReturnReason.POST_CUSTODY_CANCEL),
                eq(ReturnLane.REVERSE_FROM_DEST), eq(false), any());
        // Regression guard (state-persistence bug): the immediate RTO path must NOT re-save the caller's
        // shipment instance. open-in-view is off, so initiateReturn transitions a *different* managed
        // instance to RTO_INITIATED; re-saving this stale copy would clobber it back to AT_DEST_HUB. The
        // intent stamp (requested/resolved) is done inside initiateReturn on the locked row instead.
        verify(shipmentRepo, never()).save(any());
    }

    @Test
    void originHubOpenBagIsRecalledAndReturnedSameCity() {
        shipmentIn(ShipmentState.IN_TAKEOFF_BAG);
        when(hubRecall.recallAtOrigin(shipmentId)).thenReturn(HubRecallPort.RecallOutcome.PULLED_FROM_BAG);

        CancellationResponse r = service.initiateRtoAsAdmin("REF", "cancel", "admin-1");

        assertThat(r.disposition()).isEqualTo(Disposition.RETURN_INITIATED);
        // Pulled from an OPEN bag → the worklist item must flag a physical bag-pull (needsBagPull=true).
        verify(returnService).initiateReturn(eq(shipmentId), any(),
                eq(ReturnLane.SAME_CITY_FROM_ORIGIN), eq(true), any());
    }

    @Test
    void originHubSealedBagDefersToDestHub() {
        shipmentIn(ShipmentState.IN_TAKEOFF_BAG);
        when(hubRecall.recallAtOrigin(shipmentId)).thenReturn(HubRecallPort.RecallOutcome.COMMITTED);

        CancellationResponse r = service.initiateRtoAsAdmin("REF", "cancel", "admin-1");

        assertThat(r.disposition()).isEqualTo(Disposition.RETURN_SCHEDULED);
        verify(returnService, never()).initiateReturn(any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void committedInFlightSchedulesReverseReturn() {
        shipmentIn(ShipmentState.DEPARTED);

        CancellationResponse r = service.initiateRtoAsAdmin("REF", "cancel", "admin-1");

        assertThat(r.disposition()).isEqualTo(Disposition.RETURN_SCHEDULED);
    }

    @Test
    void terminalStateIsRejected() {
        shipmentIn(ShipmentState.DROPPED);

        assertThatThrownBy(() -> service.initiateRtoAsAdmin("REF", "cancel", "admin-1"))
                .isInstanceOf(CancellationNotAllowedException.class);
    }

    @Test
    void outForDeliveryIsNotRoutedHere() {
        shipmentIn(ShipmentState.DROP_COLLECTED);

        assertThatThrownBy(() -> service.initiateRtoAsAdmin("REF", "cancel", "admin-1"))
                .isInstanceOf(CancellationNotAllowedException.class);
    }

    @Test
    void aReturnChildCannotBeReturnedAgain() {
        Shipment s = shipmentIn(ShipmentState.AT_DEST_HUB);
        s.setReturnOfShipmentId(UUID.randomUUID());

        assertThatThrownBy(() -> service.initiateRtoAsAdmin("REF", "cancel", "admin-1"))
                .isInstanceOf(CancellationNotAllowedException.class);
    }

    @Test
    void alreadyReturningIsIdempotent() {
        Shipment s = shipmentIn(ShipmentState.AT_DEST_HUB);
        UUID childId = UUID.randomUUID();
        s.setReturnShipmentId(childId);
        Shipment child = new Shipment();
        child.setShipmentRef("REF_R");
        when(shipmentRepo.findById(childId)).thenReturn(Optional.of(child));

        CancellationResponse r = service.initiateRtoAsAdmin("REF", "cancel", "admin-1");

        assertThat(r.disposition()).isEqualTo(Disposition.RETURN_INITIATED);
        assertThat(r.returnChildRef()).isEqualTo("REF_R");
        verify(returnService, never()).initiateReturn(any(), any(), any(), anyBoolean(), any());
    }
}
