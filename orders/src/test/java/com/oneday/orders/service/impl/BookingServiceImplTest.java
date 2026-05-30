package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.DropType;
import com.oneday.common.domain.enums.PaymentMode;
import com.oneday.common.domain.enums.PickupType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.port.EtaPort;
import com.oneday.common.port.PricingPort;
import com.oneday.common.port.ServiceabilityPort;
import com.oneday.common.port.dto.EtaResult;
import com.oneday.common.port.dto.QuoteResult;
import com.oneday.common.port.dto.ServiceabilityResult;
import com.oneday.orders.domain.Address;
import com.oneday.orders.domain.PaymentTransaction;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.domain.ShipmentStateHistory;
import com.oneday.orders.dto.BookingRequest;
import com.oneday.orders.dto.BookingResponse;
import com.oneday.orders.repository.PaymentTransactionRepository;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.repository.ShipmentStateHistoryRepository;
import com.oneday.orders.service.BookingService;
import com.oneday.orders.service.CustomerVisibleStateMapper;
import com.oneday.orders.service.PaymentPort;
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
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingServiceImplTest {

    @Mock private ServiceabilityPort serviceabilityPort;
    @Mock private PricingPort pricingPort;
    @Mock private PaymentPort paymentPort;
    @Mock private EtaPort etaPort;
    @Mock private ShipmentRefService shipmentRefService;
    @Mock private ShipmentRepository shipmentRepository;
    @Mock private PaymentTransactionRepository paymentTransactionRepository;
    @Mock private ShipmentStateHistoryRepository historyRepository;

    // TransactionTemplate cannot be mocked on Java 25 (Mockito inline-mock restriction on
    // platform module types). Use a real TransactionTemplate backed by a no-op TX manager.
    private static final AbstractPlatformTransactionManager NO_OP_TX =
            new AbstractPlatformTransactionManager() {
                @Override protected Object doGetTransaction() { return new Object(); }
                @Override protected void doBegin(Object tx, TransactionDefinition def) {}
                @Override protected void doCommit(DefaultTransactionStatus s) {}
                @Override protected void doRollback(DefaultTransactionStatus s) {}
            };

    private final CustomerVisibleStateMapper stateMapper = new CustomerVisibleStateMapper();
    private ScheduledExecutorService scheduler;

    private BookingServiceImpl service;

    private static final String IDEMPOTENCY_KEY = "idem-123";
    private static final String USER_ID         = "user-abc";
    private static final String SHIPMENT_REF    = "1DD-BLR-20260530-00001";
    private static final UUID   SHIPMENT_ID     = UUID.randomUUID();
    private static final UUID   PAYMENT_ID      = UUID.randomUUID();
    private static final UUID   TILE_ID         = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();

        service = new BookingServiceImpl(
                serviceabilityPort, pricingPort, paymentPort, etaPort,
                shipmentRefService, shipmentRepository, paymentTransactionRepository,
                historyRepository, stateMapper, new TransactionTemplate(NO_OP_TX),
                CircuitBreakerRegistry.ofDefaults(),
                TimeLimiterRegistry.ofDefaults(),
                scheduler);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    // ── book() — happy path ────────────────────────────────────────────────

    @Test
    void book_happyPath_returnsConfirmationWithEta() {
        stubServiceability(true, DeliveryType.INTERCITY);
        stubPricing(4000L, 720L, 4720L);
        when(shipmentRefService.generateRef(anyString())).thenReturn(SHIPMENT_REF);
        when(shipmentRepository.save(any())).thenAnswer(inv -> {
            Shipment s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", SHIPMENT_ID);
            return s;
        });
        when(paymentTransactionRepository.save(any())).thenAnswer(inv -> {
            PaymentTransaction pt = inv.getArgument(0);
            ReflectionTestUtils.setField(pt, "id", PAYMENT_ID);
            return pt;
        });
        when(historyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(etaPort.fetchEta(any())).thenReturn(new EtaResult(Instant.now().plusSeconds(86400), 1440));

        BookingResponse resp = service.book(bookingRequest(), IDEMPOTENCY_KEY, USER_ID);

        assertThat(resp.getShipmentRef()).isEqualTo(SHIPMENT_REF);
        assertThat(resp.getState()).isEqualTo(ShipmentState.BOOKED);
        assertThat(resp.getStateLabel()).isEqualTo("Order confirmed");
        assertThat(resp.getDeliveryType()).isEqualTo(DeliveryType.INTERCITY);
        assertThat(resp.getPricing().getTotalPricePaise()).isEqualTo(4720L);
        assertThat(resp.getPricing().getGstPaise()).isEqualTo(720L);
        assertThat(resp.getPricing().getCurrency()).isEqualTo("INR");
        assertThat(resp.getEtaPromised()).isNotNull();
        assertThat(resp.getSlaCommitmentMinutes()).isEqualTo(1440);
        assertThat(resp.getLabelStatus()).isEqualTo("PENDING");
        assertThat(resp.getPayment().getStatus()).isEqualTo("CAPTURED");
        assertThat(resp.getTrackingUrl()).contains(SHIPMENT_REF);
    }

    @Test
    void book_etaFails_returnsResponseWithNullEta() {
        stubServiceability(true, DeliveryType.INTERCITY);
        stubPricing(4000L, 720L, 4720L);
        when(shipmentRefService.generateRef(anyString())).thenReturn(SHIPMENT_REF);
        when(shipmentRepository.save(any())).thenAnswer(inv -> {
            Shipment s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", SHIPMENT_ID);
            return s;
        });
        when(paymentTransactionRepository.save(any())).thenAnswer(inv -> {
            PaymentTransaction pt = inv.getArgument(0);
            ReflectionTestUtils.setField(pt, "id", PAYMENT_ID);
            return pt;
        });
        when(historyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(etaPort.fetchEta(any())).thenThrow(new RuntimeException("ETA service down"));

        BookingResponse resp = service.book(bookingRequest(), IDEMPOTENCY_KEY, USER_ID);

        assertThat(resp.getShipmentRef()).isEqualTo(SHIPMENT_REF);
        assertThat(resp.getEtaPromised()).isNull();
        assertThat(resp.getSlaCommitmentMinutes()).isNull();
    }

    // ── serviceability rejection ────────────────────────────────────────────

    @Test
    void book_notServiceable_throwsServiceabilityException() {
        stubServiceability(false, DeliveryType.INTERCITY);

        assertThatThrownBy(() -> service.book(bookingRequest(), IDEMPOTENCY_KEY, USER_ID))
                .isInstanceOf(BookingService.ServiceabilityException.class);

        verify(pricingPort, never()).computeQuote(any());
        verify(paymentPort, never()).verifySignature(any(), any(), any());
        verify(shipmentRepository, never()).save(any());
    }

    // ── payment failures ───────────────────────────────────────────────────

    @Test
    void book_paymentVerificationFails_throwsAndSkipsDbSave() {
        stubServiceability(true, DeliveryType.INTERCITY);
        stubPricing(4000L, 720L, 4720L);
        doThrow(new PaymentPort.PaymentVerificationException("Signature mismatch"))
                .when(paymentPort).verifySignature(anyString(), anyString(), anyString());

        assertThatThrownBy(() -> service.book(bookingRequest(), IDEMPOTENCY_KEY, USER_ID))
                .isInstanceOf(PaymentPort.PaymentVerificationException.class);

        verify(shipmentRepository, never()).save(any());
    }

    @Test
    void book_paymentCaptureFails_throwsAndSkipsDbSave() {
        stubServiceability(true, DeliveryType.INTERCITY);
        stubPricing(4000L, 720L, 4720L);
        doThrow(new PaymentPort.PaymentCaptureException("Insufficient funds"))
                .when(paymentPort).capture(anyString(), anyLong());

        assertThatThrownBy(() -> service.book(bookingRequest(), IDEMPOTENCY_KEY, USER_ID))
                .isInstanceOf(PaymentPort.PaymentCaptureException.class);

        verify(shipmentRepository, never()).save(any());
    }

    // ── C4: compensating refund ────────────────────────────────────────────

    @Test
    void book_dbWriteFails_initiatesCompensatingRefund() {
        stubServiceability(true, DeliveryType.INTERCITY);
        stubPricing(4000L, 720L, 4720L);
        // Trigger failure inside the TX callback so the catch block in book() fires the refund.
        when(shipmentRefService.generateRef(anyString())).thenReturn(SHIPMENT_REF);
        when(shipmentRepository.save(any())).thenThrow(new RuntimeException("DB down"));

        assertThatThrownBy(() -> service.book(bookingRequest(), IDEMPOTENCY_KEY, USER_ID))
                .isInstanceOf(RuntimeException.class);

        verify(paymentPort).initiateRefund("pay_xyz789", 4720L);
    }

    // ── shipment content ───────────────────────────────────────────────────

    @Test
    void book_chargeableWeight_usesDimensionalWhenLarger() {
        stubServiceability(true, DeliveryType.INTERCITY);

        // volumetric = (50 * 40 * 30) / 5 = 12000 g > actual 5000 g
        BookingRequest req = bookingRequest();
        req.setWeightGrams(5000);
        req.setLengthCm((short) 50);
        req.setWidthCm((short) 40);
        req.setHeightCm((short) 30);

        ArgumentCaptor<com.oneday.common.port.dto.QuoteRequest> qCaptor =
                ArgumentCaptor.forClass(com.oneday.common.port.dto.QuoteRequest.class);
        when(pricingPort.computeQuote(qCaptor.capture()))
                .thenReturn(new QuoteResult(4000L, 720L, 4720L, Map.of(), "v1"));
        when(shipmentRefService.generateRef(anyString())).thenReturn(SHIPMENT_REF);
        when(shipmentRepository.save(any())).thenAnswer(inv -> {
            Shipment s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", SHIPMENT_ID);
            return s;
        });
        when(paymentTransactionRepository.save(any())).thenAnswer(inv -> {
            PaymentTransaction pt = inv.getArgument(0);
            ReflectionTestUtils.setField(pt, "id", PAYMENT_ID);
            return pt;
        });
        when(historyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(etaPort.fetchEta(any())).thenThrow(new RuntimeException("skip"));

        service.book(req, IDEMPOTENCY_KEY, USER_ID);

        assertThat(qCaptor.getValue().chargeableWeightGrams()).isEqualTo(12000);
    }

    @Test
    void book_persistedShipment_hasCorrectCustomerTypeAndPaymentMode() {
        stubServiceability(true, DeliveryType.SAME_CITY);
        stubPricing(2000L, 360L, 2360L);
        when(shipmentRefService.generateRef(anyString())).thenReturn(SHIPMENT_REF);

        ArgumentCaptor<Shipment> sCaptor = ArgumentCaptor.forClass(Shipment.class);
        when(shipmentRepository.save(sCaptor.capture())).thenAnswer(inv -> {
            Shipment s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", SHIPMENT_ID);
            return s;
        });
        when(paymentTransactionRepository.save(any())).thenAnswer(inv -> {
            PaymentTransaction pt = inv.getArgument(0);
            ReflectionTestUtils.setField(pt, "id", PAYMENT_ID);
            return pt;
        });
        when(historyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(etaPort.fetchEta(any())).thenThrow(new RuntimeException("skip"));

        service.book(bookingRequest(), IDEMPOTENCY_KEY, USER_ID);

        Shipment saved = sCaptor.getValue();
        assertThat(saved.getCustomerType()).isEqualTo(CustomerType.B2C);
        assertThat(saved.getPaymentMode()).isEqualTo(PaymentMode.PREPAID);
        assertThat(saved.getState()).isEqualTo(ShipmentState.BOOKED);
        assertThat(saved.getIdempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
        assertThat(saved.getOriginCity()).isEqualTo("BLR");
        assertThat(saved.getDestCity()).isEqualTo("BOM");
    }

    @Test
    void book_savesInitialStateHistoryWithNullFromState() {
        stubServiceability(true, DeliveryType.INTERCITY);
        stubPricing(4000L, 720L, 4720L);
        when(shipmentRefService.generateRef(anyString())).thenReturn(SHIPMENT_REF);
        when(shipmentRepository.save(any())).thenAnswer(inv -> {
            Shipment s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", SHIPMENT_ID);
            return s;
        });
        when(paymentTransactionRepository.save(any())).thenAnswer(inv -> {
            PaymentTransaction pt = inv.getArgument(0);
            ReflectionTestUtils.setField(pt, "id", PAYMENT_ID);
            return pt;
        });
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

    private void stubServiceability(boolean serviceable, DeliveryType deliveryType) {
        when(serviceabilityPort.check(anyString(), anyString()))
                .thenReturn(new ServiceabilityResult(serviceable, TILE_ID, deliveryType));
    }

    private void stubPricing(long base, long tax, long total) {
        when(pricingPort.computeQuote(any()))
                .thenReturn(new QuoteResult(base, tax, total, Map.of("base_freight", base), "v1"));
    }

    private BookingRequest bookingRequest() {
        Address addr = new Address();
        addr.setLine1("123 Main St");
        addr.setCity("Bengaluru");
        addr.setPincode("560001");
        addr.setState("Karnataka");

        Address destAddr = new Address();
        destAddr.setLine1("456 Ring Rd");
        destAddr.setCity("Mumbai");
        destAddr.setPincode("400001");
        destAddr.setState("Maharashtra");

        BookingRequest req = new BookingRequest();
        req.setSenderName("Alice");
        req.setSenderPhone("+919876543210");
        req.setOriginAddress(addr);
        req.setOriginCity("BLR");
        req.setOriginPincode("560001");
        req.setReceiverName("Bob");
        req.setReceiverPhone("+919123456789");
        req.setDestAddress(destAddr);
        req.setDestCity("BOM");
        req.setDestPincode("400001");
        req.setWeightGrams(1000);
        req.setLengthCm((short) 20);
        req.setWidthCm((short) 15);
        req.setHeightCm((short) 10);
        req.setDeclaredValuePaise(50_000L);
        req.setPickupType(PickupType.DA_PICKUP);
        req.setDropType(DropType.DA_DELIVERY);
        req.setRazorpayOrderId("order_abc123");
        req.setRazorpayPaymentId("pay_xyz789");
        req.setRazorpaySignature("sig_test");
        return req;
    }
}
