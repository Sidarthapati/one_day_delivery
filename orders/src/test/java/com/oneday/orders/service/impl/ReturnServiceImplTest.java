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
                serviceabilityPort, pricingPort, stateMachine);

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

    @Test
    void mintsAReversedChildUnderTheSameOrder() {
        when(shipmentRepo.findById(originalId)).thenReturn(Optional.of(original(CustomerType.B2C, null)));

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
    void birthWritesHistoryAndMovesOriginalToRtoInitiated() {
        when(shipmentRepo.findById(originalId)).thenReturn(Optional.of(original(CustomerType.B2C, null)));

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
        when(shipmentRepo.findById(originalId)).thenReturn(Optional.of(original(CustomerType.B2B, acctId)));

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
        when(shipmentRepo.findById(originalId)).thenReturn(Optional.of(orig));
        when(shipmentRepo.findById(childId)).thenReturn(Optional.of(existing));

        ReturnService.ReturnResult r = service.initiateReturn(originalId, ReturnReason.ATTEMPTS_EXHAUSTED, ctx);

        assertThat(r.childShipmentRef()).isEqualTo("1DD-DEL-20260828-00001_R");
        verify(shipmentRepo, never()).save(any());
        verify(stateMachine, never()).transition(any(), any(), any());
    }
}
