package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.PaymentMode;
import com.oneday.common.domain.enums.ReturnReason;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.port.PricingPort;
import com.oneday.common.port.ServiceabilityPort;
import com.oneday.common.port.dto.QuoteResult;
import com.oneday.common.port.dto.ServiceabilityResult;
import com.oneday.orders.domain.Address;
import com.oneday.orders.domain.B2bAccount;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.domain.ShipmentStateHistory;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.repository.ShipmentStateHistoryRepository;
import com.oneday.orders.service.OrderService;
import com.oneday.orders.service.ReturnService;
import com.oneday.orders.service.ShipmentStateMachine;
import com.oneday.orders.service.TransitionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for the return-child mint/price/bill/link path (repos + ports mocked). */
class ReturnServiceImplTest {

    private ShipmentRepository shipmentRepo;
    private ShipmentStateHistoryRepository historyRepo;
    private B2bAccountRepository accountRepo;
    private OrderService orderService;
    private ServiceabilityPort serviceabilityPort;
    private PricingPort pricingPort;
    private ShipmentStateMachine stateMachine;
    private ReturnServiceImpl service;

    private final UUID originalId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final TransitionContext ctx = TransitionContext.fromSystem("test");

    @BeforeEach
    void setUp() {
        shipmentRepo = mock(ShipmentRepository.class);
        historyRepo = mock(ShipmentStateHistoryRepository.class);
        accountRepo = mock(B2bAccountRepository.class);
        orderService = mock(OrderService.class);
        serviceabilityPort = mock(ServiceabilityPort.class);
        pricingPort = mock(PricingPort.class);
        stateMachine = mock(ShipmentStateMachine.class);
        service = new ReturnServiceImpl(shipmentRepo, historyRepo, accountRepo, orderService,
                serviceabilityPort, pricingPort, stateMachine,
                mock(com.oneday.orders.service.RtoWorklistService.class));

        when(shipmentRepo.save(any(Shipment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(serviceabilityPort.check(any()))
                .thenReturn(new ServiceabilityResult(true, UUID.randomUUID(), UUID.randomUUID(), DeliveryType.INTERCITY));
        when(pricingPort.computeQuote(any()))
                .thenReturn(new QuoteResult(9000L, 1000L, 10000L, Map.of(), "rc-v1"));
    }

    private Shipment original(CustomerType type, UUID b2bAccountId) {
        Address origin = new Address();
        origin.setLatitude(28.61);
        origin.setLongitude(77.20);
        Address dest = new Address();
        dest.setLatitude(19.07);
        dest.setLongitude(72.87);
        Shipment s = new Shipment();
        ReflectionTestUtils.setField(s, "id", originalId);
        s.setShipmentRef("1DD-DEL-20260828-00001");
        s.setCustomerType(type);
        s.setB2bAccountId(b2bAccountId);
        s.setOrderId(orderId);
        s.setSenderName("Aarav"); s.setSenderPhone("+919000000001"); s.setSenderEmail("aarav@ex.com");
        s.setReceiverName("Bala"); s.setReceiverPhone("+919000000002"); s.setReceiverEmail("bala@ex.com");
        s.setOriginAddress(origin); s.setOriginCity("DEL"); s.setOriginPincode("110001");
        s.setDestAddress(dest); s.setDestCity("BOM"); s.setDestPincode("400001");
        s.setWeightGrams(2000); s.setChargeableWeightGrams(2000);
        s.setDeclaredValuePaise(500000L);
        s.setState(ShipmentState.DELIVERY_FAILED);
        return s;
    }

    private Shipment capturedChild() {
        ArgumentCaptor<Shipment> cap = ArgumentCaptor.forClass(Shipment.class);
        verify(shipmentRepo, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
        return cap.getAllValues().stream()
                .filter(s -> s.getShipmentRef() != null && s.getShipmentRef().endsWith("_R"))
                .findFirst().orElseThrow();
    }

    private Shipment pendingRtoOriginal() {
        Shipment s = original(CustomerType.B2C, null);
        s.setRtoRequestedAt(java.time.Instant.now());   // intent stamped, not yet resolved, no child
        return s;
    }

    // One-scan RTO at the origin hub: a pending same-city intent mints a SAME_CITY child (return origin =
    // original origin) and drives both the arrival and the RTO_INITIATED transitions.
    @Test
    void resolveOnHubArrival_originHub_mintsSameCityChild() {
        Shipment original = pendingRtoOriginal();
        when(shipmentRepo.findById(originalId)).thenReturn(Optional.of(original));
        when(shipmentRepo.findByIdWithLock(originalId)).thenReturn(Optional.of(original));
        when(shipmentRepo.findByReturnOfShipmentId(originalId)).thenReturn(Optional.empty());

        Optional<ReturnService.ReturnResult> result =
                service.resolveOnHubArrival(originalId, ShipmentState.AT_ORIGIN_HUB, ctx);

        assertThat(result).isPresent();
        verify(stateMachine).transition(originalId, ShipmentState.AT_ORIGIN_HUB, ctx);
        verify(stateMachine).transition(originalId, ShipmentState.RTO_INITIATED, ctx);
        assertThat(capturedChild().getOriginCity()).isEqualTo("DEL"); // same-city: return origin = origin
    }

    // One-scan RTO at the dest hub: a pending intent mints a REVERSE child (return origin = original dest).
    @Test
    void resolveOnHubArrival_destHub_mintsReverseChild() {
        Shipment original = pendingRtoOriginal();
        when(shipmentRepo.findById(originalId)).thenReturn(Optional.of(original));
        when(shipmentRepo.findByIdWithLock(originalId)).thenReturn(Optional.of(original));
        when(shipmentRepo.findByReturnOfShipmentId(originalId)).thenReturn(Optional.empty());

        Optional<ReturnService.ReturnResult> result =
                service.resolveOnHubArrival(originalId, ShipmentState.AT_DEST_HUB, ctx);

        assertThat(result).isPresent();
        verify(stateMachine).transition(originalId, ShipmentState.AT_DEST_HUB, ctx);
        assertThat(capturedChild().getOriginCity()).isEqualTo("BOM"); // reverse: return origin = dest
    }

    // No pending RTO → no-op: empty result, no arrival transition, no child minted.
    @Test
    void resolveOnHubArrival_noPendingIntent_returnsEmpty() {
        Shipment original = original(CustomerType.B2C, null); // no rtoRequestedAt
        when(shipmentRepo.findByIdWithLock(originalId)).thenReturn(Optional.of(original));

        Optional<ReturnService.ReturnResult> result =
                service.resolveOnHubArrival(originalId, ShipmentState.AT_ORIGIN_HUB, ctx);

        assertThat(result).isEmpty();
        verify(stateMachine, never()).transition(any(), any(), any());
    }

    // Race: the async resolver / a concurrent scan resolved the intent and minted the child before this
    // locked load. Hand the existing child back (so the caller sorts it) instead of failing /receive.
    @Test
    void resolveOnHubArrival_alreadyResolved_returnsExistingChildWithoutTransition() {
        Shipment original = pendingRtoOriginal();
        UUID childId = UUID.randomUUID();
        original.setRtoResolvedAt(java.time.Instant.now());
        original.setReturnShipmentId(childId);
        Shipment child = new Shipment();
        ReflectionTestUtils.setField(child, "id", childId);
        child.setShipmentRef("1DD-DEL-20260828-00001_R");
        when(shipmentRepo.findByIdWithLock(originalId)).thenReturn(Optional.of(original));
        when(shipmentRepo.findById(childId)).thenReturn(Optional.of(child));

        Optional<ReturnService.ReturnResult> result =
                service.resolveOnHubArrival(originalId, ShipmentState.AT_DEST_HUB, ctx);

        assertThat(result).isPresent();
        assertThat(result.get().childShipmentRef()).isEqualTo("1DD-DEL-20260828-00001_R");
        verify(stateMachine, never()).transition(any(), any(), any()); // no re-transition out of RTO_INITIATED
        verify(shipmentRepo, never()).save(any());
    }

    // LANDED has no direct edge to AT_DEST_HUB — stage it through DISPATCHED_TO_HUB before arrival.
    @Test
    void resolveOnHubArrival_landed_stagesThroughDispatchedToHub() {
        Shipment original = pendingRtoOriginal();
        original.setState(ShipmentState.LANDED);
        when(shipmentRepo.findById(originalId)).thenReturn(Optional.of(original));
        when(shipmentRepo.findByIdWithLock(originalId)).thenReturn(Optional.of(original));
        when(shipmentRepo.findByReturnOfShipmentId(originalId)).thenReturn(Optional.empty());

        service.resolveOnHubArrival(originalId, ShipmentState.AT_DEST_HUB, ctx);

        verify(stateMachine).transition(originalId, ShipmentState.DISPATCHED_TO_HUB, ctx);
        verify(stateMachine).transition(originalId, ShipmentState.AT_DEST_HUB, ctx);
    }

    // A re-scan of a parcel already at the hub state must not request an illegal self-transition.
    @Test
    void resolveOnHubArrival_alreadyAtHubState_skipsArrivalTransition() {
        Shipment original = pendingRtoOriginal();
        original.setState(ShipmentState.AT_ORIGIN_HUB);
        when(shipmentRepo.findById(originalId)).thenReturn(Optional.of(original));
        when(shipmentRepo.findByIdWithLock(originalId)).thenReturn(Optional.of(original));
        when(shipmentRepo.findByReturnOfShipmentId(originalId)).thenReturn(Optional.empty());

        Optional<ReturnService.ReturnResult> result =
                service.resolveOnHubArrival(originalId, ShipmentState.AT_ORIGIN_HUB, ctx);

        assertThat(result).isPresent();
        verify(stateMachine, never()).transition(originalId, ShipmentState.AT_ORIGIN_HUB, ctx); // no self-edge
        verify(stateMachine).transition(originalId, ShipmentState.RTO_INITIATED, ctx);          // mint still runs
    }

    @Test
    void mintsAReversedChildUnderTheSameOrder() {
        when(shipmentRepo.findByIdWithLock(originalId)).thenReturn(Optional.of(original(CustomerType.B2C, null)));

        ReturnService.ReturnResult r = service.initiateReturn(originalId, ReturnReason.ATTEMPTS_EXHAUSTED, ctx);

        assertThat(r.childShipmentRef()).isEqualTo("1DD-DEL-20260828-00001_R");
        Shipment child = capturedChild();
        assertThat(child.getReturnOfShipmentId()).isEqualTo(originalId);
        assertThat(child.getOrderId()).isEqualTo(orderId);
        // Reversed geography: return origin = original dest, return dest = original sender.
        assertThat(child.getOriginCity()).isEqualTo("BOM");
        assertThat(child.getDestCity()).isEqualTo("DEL");
        assertThat(child.getSenderName()).isEqualTo("Bala");
        assertThat(child.getReceiverName()).isEqualTo("Aarav");
        assertThat(child.getState()).isEqualTo(ShipmentState.AT_ORIGIN_HUB);
        assertThat(child.getTotalPricePaise()).isEqualTo(10000L);
        assertThat(child.getPaymentMode()).isEqualTo(PaymentMode.PREPAID); // never COD on a return
        verify(orderService).addShipment(orderId, 10000L);
    }

    @Test
    void sameCityLaneReturnsToTheSenderWithinTheOriginCity() {
        Shipment orig = original(CustomerType.B2C, null);
        orig.setState(ShipmentState.AT_ORIGIN_HUB);
        when(shipmentRepo.findByIdWithLock(originalId)).thenReturn(Optional.of(orig));

        service.initiateReturn(originalId, ReturnReason.POST_CUSTODY_CANCEL,
                ReturnService.ReturnLane.SAME_CITY_FROM_ORIGIN, ctx);

        Shipment child = capturedChild();
        // Same-city: never left the origin city — both ends are DEL, delivered back to the sender.
        assertThat(child.getOriginCity()).isEqualTo("DEL");
        assertThat(child.getDestCity()).isEqualTo("DEL");
        assertThat(child.getReceiverName()).isEqualTo("Aarav"); // original sender
        assertThat(child.getState()).isEqualTo(ShipmentState.AT_ORIGIN_HUB);
        verify(stateMachine).transition(eq(originalId), eq(ShipmentState.RTO_INITIATED), any());
        // POST_CUSTODY_CANCEL stamps the RTO intent on the *locked* original here (in this persistence
        // context), so it commits with the transition — the cancel router / deferred resolver must not
        // re-save their own stale copies (open-in-view is off). This is the fix for the state-persistence
        // bug where a stale re-save clobbered RTO_INITIATED back to the hub state.
        assertThat(orig.getRtoRequestedAt()).isNotNull();
        assertThat(orig.getRtoResolvedAt()).isNotNull();
    }

    @Test
    void birthWritesHistoryAndMovesOriginalToRtoInitiated() {
        when(shipmentRepo.findByIdWithLock(originalId)).thenReturn(Optional.of(original(CustomerType.B2C, null)));

        service.initiateReturn(originalId, ReturnReason.ATTEMPTS_EXHAUSTED, ctx);

        ArgumentCaptor<ShipmentStateHistory> h = ArgumentCaptor.forClass(ShipmentStateHistory.class);
        verify(historyRepo).save(h.capture());
        assertThat(h.getValue().getToState()).isEqualTo(ShipmentState.AT_ORIGIN_HUB);
        assertThat(h.getValue().getFromState()).isNull();
        verify(stateMachine).transition(eq(originalId), eq(ShipmentState.RTO_INITIATED), any());
    }

    @Test
    void billsB2bPastCreditLimitWithNoCheck() {
        UUID acctId = UUID.randomUUID();
        B2bAccount acct = new B2bAccount();
        acct.setOutstandingBalancePaise(5000L);
        acct.setCreditLimitPaise(6000L);              // already near the limit
        acct.setRateCardId(UUID.randomUUID());
        when(accountRepo.findByIdForUpdate(acctId)).thenReturn(Optional.of(acct));
        when(shipmentRepo.findByIdWithLock(originalId)).thenReturn(Optional.of(original(CustomerType.B2B, acctId)));

        service.initiateReturn(originalId, ReturnReason.ATTEMPTS_EXHAUSTED, ctx);

        assertThat(acct.getOutstandingBalancePaise()).isEqualTo(15000L); // 5000 + 10000, over the 6000 limit
        assertThat(capturedChild().getPaymentMode()).isNull();           // B2B carries no payment mode
    }

    @Test
    void isIdempotentWhenAReturnChildAlreadyExists() {
        Shipment orig = original(CustomerType.B2C, null);
        UUID childId = UUID.randomUUID();
        orig.setReturnShipmentId(childId);
        Shipment existing = new Shipment();
        existing.setShipmentRef("1DD-DEL-20260828-00001_R");
        when(shipmentRepo.findByIdWithLock(originalId)).thenReturn(Optional.of(orig));
        when(shipmentRepo.findById(childId)).thenReturn(Optional.of(existing));

        ReturnService.ReturnResult r = service.initiateReturn(originalId, ReturnReason.ATTEMPTS_EXHAUSTED, ctx);

        assertThat(r.childShipmentRef()).isEqualTo("1DD-DEL-20260828-00001_R");
        verify(shipmentRepo, never()).save(any());
        verify(stateMachine, never()).transition(any(), any(), any());
    }
}
