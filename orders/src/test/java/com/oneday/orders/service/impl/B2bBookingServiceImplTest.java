package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.DropType;
import com.oneday.common.domain.enums.PickupType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.port.EtaPort;
import com.oneday.common.port.PricingPort;
import com.oneday.common.port.ServiceabilityPort;
import com.oneday.common.port.dto.EtaResult;
import com.oneday.common.port.dto.QuoteResult;
import com.oneday.common.port.dto.ServiceabilityQuery;
import com.oneday.common.port.dto.ServiceabilityResult;
import com.oneday.orders.domain.Address;
import com.oneday.orders.domain.B2bAccount;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.domain.ShipmentStateHistory;
import com.oneday.orders.dto.B2bBookingRequest;
import com.oneday.orders.dto.BookingResponse;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.repository.ShipmentStateHistoryRepository;
import com.oneday.orders.service.B2bBookingService;
import com.oneday.orders.service.CustomerVisibleStateMapper;
import com.oneday.orders.service.ShipmentRefService;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class B2bBookingServiceImplTest {

    @Mock private B2bAccountRepository b2bAccountRepository;
    @Mock private ServiceabilityPort serviceabilityPort;
    @Mock private PricingPort pricingPort;
    @Mock private EtaPort etaPort;
    @Mock private ShipmentRefService shipmentRefService;
    @Mock private ShipmentRepository shipmentRepository;
    @Mock private ShipmentStateHistoryRepository historyRepository;

    private static final AbstractPlatformTransactionManager NO_OP_TX =
            new AbstractPlatformTransactionManager() {
                @Override protected Object doGetTransaction() { return new Object(); }
                @Override protected void doBegin(Object tx, TransactionDefinition def) {}
                @Override protected void doCommit(DefaultTransactionStatus s) {}
                @Override protected void doRollback(DefaultTransactionStatus s) {}
            };

    private final CustomerVisibleStateMapper stateMapper = new CustomerVisibleStateMapper();
    private ScheduledExecutorService scheduler;
    private B2bBookingServiceImpl service;

    private static final String IDEMPOTENCY_KEY = "idem-b2b-123";
    private static final String USER_ID         = "user-b2b-abc";
    private static final String SHIPMENT_REF    = "1DD-BLR-20260530-00001";
    private static final UUID   SHIPMENT_ID     = UUID.randomUUID();
    private static final UUID   ACCOUNT_ID      = UUID.randomUUID();
    private static final UUID   TILE_ID         = UUID.randomUUID();
    private static final UUID   RATE_CARD_ID    = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        service = new B2bBookingServiceImpl(
                b2bAccountRepository, serviceabilityPort, pricingPort, etaPort,
                shipmentRefService, shipmentRepository, historyRepository,
                stateMapper, new TransactionTemplate(NO_OP_TX),
                CircuitBreakerRegistry.ofDefaults(),
                TimeLimiterRegistry.ofDefaults(),
                scheduler);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    // ── happy path ─────────────────────────────────────────────────────────

    @Test
    void book_happyPath_setsB2bFieldsAndIncrementsBalance() {
        B2bAccount account = activeAccount(500_000L, 1_000_000L);
        stubAccount(account);
        stubServiceability(true, DeliveryType.INTERCITY);
        stubPricing(4000L, 720L, 4720L);
        when(shipmentRefService.generateRef(anyString())).thenReturn(SHIPMENT_REF);

        ArgumentCaptor<Shipment> shipmentCaptor = ArgumentCaptor.forClass(Shipment.class);
        ArgumentCaptor<B2bAccount> accountCaptor = ArgumentCaptor.forClass(B2bAccount.class);
        when(shipmentRepository.save(shipmentCaptor.capture())).thenAnswer(inv -> {
            Shipment s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", SHIPMENT_ID);
            return s;
        });
        when(b2bAccountRepository.save(accountCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));
        when(historyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(etaPort.fetchEta(any())).thenReturn(new EtaResult(Instant.now().plusSeconds(86400), 1440));

        BookingResponse resp = service.book(bookingRequest(), IDEMPOTENCY_KEY, USER_ID);

        // Shipment fields
        Shipment saved = shipmentCaptor.getValue();
        assertThat(saved.getCustomerType()).isEqualTo(CustomerType.B2B);
        assertThat(saved.getB2bAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(saved.getPaymentMode()).isNull();
        assertThat(saved.getState()).isEqualTo(ShipmentState.BOOKED);
        assertThat(saved.getIdempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);

        // Outstanding balance incremented by total price
        B2bAccount savedAccount = accountCaptor.getValue();
        assertThat(savedAccount.getOutstandingBalancePaise()).isEqualTo(500_000L + 4720L);

        // Response has no payment block
        assertThat(resp.getShipmentRef()).isEqualTo(SHIPMENT_REF);
        assertThat(resp.getState()).isEqualTo(ShipmentState.BOOKED);
        assertThat(resp.getPayment()).isNull();
        assertThat(resp.getEtaPromised()).isNotNull();
        assertThat(resp.getSlaCommitmentMinutes()).isEqualTo(1440);
    }

    // ── account not found ──────────────────────────────────────────────────

    @Test
    void book_accountNotFound_throwsAccountNotFoundException() {
        when(b2bAccountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.book(bookingRequest(), IDEMPOTENCY_KEY, USER_ID))
                .isInstanceOf(B2bBookingService.AccountNotFoundException.class);

        verify(serviceabilityPort, never()).check(any());
        verify(shipmentRepository, never()).save(any());
    }

    // ── account inactive ───────────────────────────────────────────────────

    @Test
    void book_accountInactive_throwsAccountInactiveException() {
        B2bAccount inactive = activeAccount(0L, 1_000_000L);
        inactive.setIsActive(false);
        when(b2bAccountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service.book(bookingRequest(), IDEMPOTENCY_KEY, USER_ID))
                .isInstanceOf(B2bBookingService.AccountInactiveException.class);

        verify(serviceabilityPort, never()).check(any());
        verify(shipmentRepository, never()).save(any());
    }

    // ── credit limit exceeded ──────────────────────────────────────────────

    @Test
    void book_creditLimitExceeded_throwsCreditLimitExceededException() {
        // Account has 0 headroom: outstanding == creditLimit
        B2bAccount account = activeAccount(1_000_000L, 1_000_000L);
        stubAccount(account);
        stubServiceability(true, DeliveryType.INTERCITY);
        stubPricing(4000L, 720L, 4720L);
        // generateRef is NOT stubbed — the credit check throws before it is reached

        assertThatThrownBy(() -> service.book(bookingRequest(), IDEMPOTENCY_KEY, USER_ID))
                .isInstanceOf(B2bBookingService.CreditLimitExceededException.class);

        verify(shipmentRepository, never()).save(any());
    }

    // ── DB write fails — no refund attempted ──────────────────────────────

    @Test
    void book_dbWriteFails_doesNotAttemptAnyRefund() {
        B2bAccount account = activeAccount(0L, 1_000_000L);
        stubAccount(account);
        stubServiceability(true, DeliveryType.INTERCITY);
        stubPricing(4000L, 720L, 4720L);
        when(shipmentRefService.generateRef(anyString())).thenReturn(SHIPMENT_REF);
        when(shipmentRepository.save(any())).thenThrow(new RuntimeException("DB down"));

        assertThatThrownBy(() -> service.book(bookingRequest(), IDEMPOTENCY_KEY, USER_ID))
                .isInstanceOf(RuntimeException.class);

        // B2B has no payment port — the test simply confirms no PaymentPort interaction
        // (there is no PaymentPort in this service; the absence of a @Mock field is the assertion)
        verify(shipmentRepository).save(any());  // was called (and threw)
    }

    // ── ETA failure ────────────────────────────────────────────────────────

    @Test
    void book_etaFails_returnsResponseWithNullEta() {
        B2bAccount account = activeAccount(0L, 1_000_000L);
        stubAccount(account);
        stubServiceability(true, DeliveryType.INTERCITY);
        stubPricing(4000L, 720L, 4720L);
        when(shipmentRefService.generateRef(anyString())).thenReturn(SHIPMENT_REF);
        when(shipmentRepository.save(any())).thenAnswer(inv -> {
            Shipment s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", SHIPMENT_ID);
            return s;
        });
        when(b2bAccountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(historyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(etaPort.fetchEta(any())).thenThrow(new RuntimeException("ETA service down"));

        BookingResponse resp = service.book(bookingRequest(), IDEMPOTENCY_KEY, USER_ID);

        assertThat(resp.getShipmentRef()).isEqualTo(SHIPMENT_REF);
        assertThat(resp.getEtaPromised()).isNull();
        assertThat(resp.getSlaCommitmentMinutes()).isNull();
    }

    // ── state history ──────────────────────────────────────────────────────

    @Test
    void book_savesInitialStateHistoryWithNullFromState() {
        B2bAccount account = activeAccount(0L, 1_000_000L);
        stubAccount(account);
        stubServiceability(true, DeliveryType.INTERCITY);
        stubPricing(4000L, 720L, 4720L);
        when(shipmentRefService.generateRef(anyString())).thenReturn(SHIPMENT_REF);
        when(shipmentRepository.save(any())).thenAnswer(inv -> {
            Shipment s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", SHIPMENT_ID);
            return s;
        });
        when(b2bAccountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<ShipmentStateHistory> histCaptor =
                ArgumentCaptor.forClass(ShipmentStateHistory.class);
        when(historyRepository.save(histCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));
        when(etaPort.fetchEta(any())).thenThrow(new RuntimeException("skip"));

        service.book(bookingRequest(), IDEMPOTENCY_KEY, USER_ID);

        ShipmentStateHistory hist = histCaptor.getValue();
        assertThat(hist.getFromState()).isNull();
        assertThat(hist.getToState()).isEqualTo(ShipmentState.BOOKED);
        assertThat(hist.getShipmentId()).isEqualTo(SHIPMENT_ID);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void stubAccount(B2bAccount account) {
        when(b2bAccountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(b2bAccountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
    }

    private void stubServiceability(boolean serviceable, DeliveryType deliveryType) {
        when(serviceabilityPort.check(any(ServiceabilityQuery.class)))
                .thenReturn(new ServiceabilityResult(serviceable, TILE_ID, TILE_ID, deliveryType));
    }

    private void stubPricing(long base, long tax, long total) {
        when(pricingPort.computeQuote(any()))
                .thenReturn(new QuoteResult(base, tax, total, Map.of("base_freight", base), "v1"));
    }

    private B2bAccount activeAccount(long outstanding, long creditLimit) {
        B2bAccount acc = new B2bAccount();
        ReflectionTestUtils.setField(acc, "id", ACCOUNT_ID);
        acc.setAccountName("Acme Corp");
        acc.setBillingEmail("acme@test.com");
        acc.setCreditLimitPaise(creditLimit);
        acc.setOutstandingBalancePaise(outstanding);
        acc.setPaymentTermsDays((short) 30);
        acc.setCityId("BLR");
        acc.setIsActive(true);
        acc.setRateCardId(RATE_CARD_ID);
        return acc;
    }

    private B2bBookingRequest bookingRequest() {
        Address origin = new Address();
        origin.setLine1("123 Main St");
        origin.setCity("Bengaluru");
        origin.setPincode("560001");
        origin.setState("Karnataka");

        Address dest = new Address();
        dest.setLine1("456 Ring Rd");
        dest.setCity("Mumbai");
        dest.setPincode("400001");
        dest.setState("Maharashtra");

        B2bBookingRequest req = new B2bBookingRequest();
        req.setB2bAccountId(ACCOUNT_ID);
        req.setPurchaseOrderRef("PO-2026-001");
        req.setSenderName("Alice");
        req.setSenderPhone("+919876543210");
        req.setOriginAddress(origin);
        req.setOriginCity("BLR");
        req.setOriginPincode("560001");
        req.setReceiverName("Bob");
        req.setReceiverPhone("+919123456789");
        req.setDestAddress(dest);
        req.setDestCity("BOM");
        req.setDestPincode("400001");
        req.setWeightGrams(1000);
        req.setLengthCm((short) 20);
        req.setWidthCm((short) 15);
        req.setHeightCm((short) 10);
        req.setDeclaredValuePaise(50_000L);
        req.setPickupType(PickupType.DA_PICKUP);
        req.setDropType(DropType.DA_DELIVERY);
        return req;
    }
}
