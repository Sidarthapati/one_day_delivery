package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.PaymentMode;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.port.EtaPort;
import com.oneday.common.port.PricingPort;
import com.oneday.common.port.ServiceabilityPort;
import com.oneday.common.port.dto.EtaContext;
import com.oneday.common.port.dto.EtaRequest;
import com.oneday.common.port.dto.EtaResult;
import com.oneday.common.port.dto.QuoteRequest;
import com.oneday.common.port.dto.QuoteResult;
import com.oneday.common.port.dto.ServiceabilityQuery;
import com.oneday.common.port.dto.ServiceabilityResult;
import com.oneday.orders.domain.PaymentTransaction;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.domain.ShipmentStateHistory;
import com.oneday.orders.domain.enums.PaymentStatus;
import com.oneday.orders.dto.BookingRequest;
import com.oneday.orders.dto.BookingResponse;
import com.oneday.orders.events.ShipmentBooked;
import com.oneday.orders.repository.PaymentTransactionRepository;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.repository.ShipmentStateHistoryRepository;
import com.oneday.orders.service.BookingService;
import com.oneday.orders.service.CustomerVisibleStateMapper;
import com.oneday.orders.service.PaymentPort;
import com.oneday.orders.service.ShipmentRefService;
import com.oneday.orders.service.TransitionContext;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@Service
class BookingServiceImpl implements BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingServiceImpl.class);

    private static final String PAYMENT_STATUS_CAPTURED    = "CAPTURED";
    private static final String PAYMENT_STATUS_COD_PENDING = "COD_PENDING";

    private final ServiceabilityPort serviceabilityPort;
    private final PricingPort pricingPort;
    private final PaymentPort paymentPort;
    private final EtaPort etaPort;
    private final ShipmentRefService shipmentRefService;
    private final ShipmentRepository shipmentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final ShipmentStateHistoryRepository historyRepository;
    private final CustomerVisibleStateMapper stateMapper;
    private final TransactionTemplate tx;

    private final CircuitBreaker serviceabilityCb;
    private final CircuitBreaker pricingCb;
    private final CircuitBreaker paymentCb;

    private final TimeLimiter serviceabilityTl;
    private final TimeLimiter pricingTl;
    private final TimeLimiter paymentTl;

    private final ScheduledExecutorService scheduler;
    private final ApplicationEventPublisher applicationEventPublisher;

    BookingServiceImpl(ServiceabilityPort serviceabilityPort,
                       PricingPort pricingPort,
                       PaymentPort paymentPort,
                       EtaPort etaPort,
                       ShipmentRefService shipmentRefService,
                       ShipmentRepository shipmentRepository,
                       PaymentTransactionRepository paymentTransactionRepository,
                       ShipmentStateHistoryRepository historyRepository,
                       CustomerVisibleStateMapper stateMapper,
                       TransactionTemplate transactionTemplate,
                       CircuitBreakerRegistry circuitBreakerRegistry,
                       TimeLimiterRegistry timeLimiterRegistry,
                       @Qualifier("resilienceScheduler") ScheduledExecutorService resilienceScheduler,
                       ApplicationEventPublisher applicationEventPublisher) {
        this.serviceabilityPort = serviceabilityPort;
        this.pricingPort = pricingPort;
        this.paymentPort = paymentPort;
        this.etaPort = etaPort;
        this.shipmentRefService = shipmentRefService;
        this.shipmentRepository = shipmentRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.historyRepository = historyRepository;
        this.stateMapper = stateMapper;
        this.tx = transactionTemplate;
        this.serviceabilityCb = circuitBreakerRegistry.circuitBreaker("serviceability");
        this.pricingCb        = circuitBreakerRegistry.circuitBreaker("pricing");
        this.paymentCb        = circuitBreakerRegistry.circuitBreaker("payment");
        this.serviceabilityTl = timeLimiterRegistry.timeLimiter("serviceability");
        this.pricingTl        = timeLimiterRegistry.timeLimiter("pricing");
        this.paymentTl        = timeLimiterRegistry.timeLimiter("payment");
        this.scheduler        = resilienceScheduler;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public QuoteResult quote(BookingRequest req) {
        // Retail pre-quote for the payment flow. B2C and C2C share the retail rate card, so B2C is a
        // safe default here — the booking total computed later with the real type matches.
        return priceRequest(req, CustomerType.B2C).quote();
    }

    @Override
    public BookingResponse bookSettled(BookingRequest req, String idempotencyKey, String userId,
                                       CustomerType customerType) {
        // Cart checkout already settled payment once for the whole cart, so we re-price (to catch
        // staleness) and persist — but write NO per-shipment PaymentTransaction and take no payment.
        Priced priced = priceRequest(req, customerType);
        return tx.execute(status ->
                persist(req, idempotencyKey, userId, customerType, priced.serviceability(),
                        priced.volumetricWeightGrams(), priced.chargeableWeightGrams(), priced.quote(),
                        /* recordPayment */ false));
    }

    // Steps 1-3 of booking, with no payment or DB write — reused by book() and quote()
    // (the payment create-order flow prices the shipment via quote() before checkout).
    private Priced priceRequest(BookingRequest req, CustomerType customerType) {
        // ── 1. Serviceability (outside DB transaction) ─────────────────────────
        ServiceabilityResult serviceability = callWithTimeout(serviceabilityTl, serviceabilityCb,
                () -> serviceabilityPort.check(new ServiceabilityQuery(
                        req.getOriginPincode(), req.getDestPincode(),
                        req.getOriginAddress().getLatitude(), req.getOriginAddress().getLongitude(),
                        req.getDestAddress().getLatitude(), req.getDestAddress().getLongitude())));

        if (!serviceability.serviceable()) {
            throw new ServiceabilityException(
                    "Route not serviceable: " + req.getOriginPincode() + " → " + req.getDestPincode());
        }

        // ── 2. Weight calculation ──────────────────────────────────────────────
        // L(cm)*W(cm)*H(cm) / 5000 gives volumetric weight in kg; multiply by 1000 for grams → divide by 5
        int volumetricWeightGrams = (req.getLengthCm() * req.getWidthCm() * req.getHeightCm()) / 5;
        int chargeableWeightGrams = Math.max(req.getWeightGrams(), volumetricWeightGrams);

        // ── 3. Pricing (outside DB transaction) ───────────────────────────────
        QuoteResult quote = callWithTimeout(pricingTl, pricingCb, () -> pricingPort.computeQuote(
                new QuoteRequest(
                        customerType,
                        serviceability.deliveryType(),
                        req.getOriginCity().toUpperCase(),
                        req.getDestCity().toUpperCase(),
                        chargeableWeightGrams,
                        req.getDeclaredValuePaise(),
                        null,
                        req.getPaymentMode())));
        return new Priced(serviceability, volumetricWeightGrams, chargeableWeightGrams, quote);
    }

    private record Priced(ServiceabilityResult serviceability, int volumetricWeightGrams,
                          int chargeableWeightGrams, QuoteResult quote) {}

    @Override
    public BookingResponse book(BookingRequest req, String idempotencyKey, String userId) {
        return book(req, idempotencyKey, userId, CustomerType.B2C);
    }

    @Override
    public BookingResponse book(BookingRequest req, String idempotencyKey, String userId,
                                CustomerType customerType) {
        Priced priced = priceRequest(req, customerType);
        ServiceabilityResult serviceability = priced.serviceability();
        int volumetricWeightGrams = priced.volumetricWeightGrams();
        int chargeableWeightGrams = priced.chargeableWeightGrams();
        QuoteResult quote = priced.quote();

        // ── 4. Payment verify + capture (PREPAID only, outside DB transaction) ──
        boolean isPrepaid = PaymentMode.PREPAID == req.getPaymentMode();
        if (isPrepaid) {
            if (req.getRazorpayOrderId() == null || req.getRazorpayOrderId().isBlank() ||
                req.getRazorpayPaymentId() == null || req.getRazorpayPaymentId().isBlank() ||
                req.getRazorpaySignature() == null || req.getRazorpaySignature().isBlank()) {
                throw new BookingService.InvalidBookingRequestException(
                        "razorpayOrderId, razorpayPaymentId, and razorpaySignature are required for PREPAID bookings");
            }
            runWithTimeout(paymentTl, paymentCb, () -> {
                paymentPort.verifySignature(
                        req.getRazorpayOrderId(),
                        req.getRazorpayPaymentId(),
                        req.getRazorpaySignature());
                paymentPort.capture(req.getRazorpayPaymentId(), quote.totalPricePaise());
            });
        }

        // ── 5. DB writes (transaction opened only here) ────────────────────────
        // For PREPAID: if the DB write fails after capture, initiate a compensating refund.
        final int finalVolumetricWeight = volumetricWeightGrams;
        final int finalChargeableWeight = chargeableWeightGrams;
        try {
            return tx.execute(status ->
                    persist(req, idempotencyKey, userId, customerType, serviceability,
                            finalVolumetricWeight, finalChargeableWeight, quote, /* recordPayment */ true));
        } catch (RuntimeException dbEx) {
            if (isPrepaid) {
                try {
                    String refundId = paymentPort.initiateRefund(req.getRazorpayPaymentId(), quote.totalPricePaise());
                    // Log refund ID explicitly — the PaymentTransaction row was rolled back with the
                    // failed TX so this log line is the only audit trail for this money movement.
                    log.error("COMPENSATING REFUND INITIATED: paymentId={} refundId={} amountPaise={} reason=db-write-failure",
                            req.getRazorpayPaymentId(), refundId, quote.totalPricePaise());
                } catch (Exception refundEx) {
                    log.error("MANUAL INTERVENTION REQUIRED: payment {} for {} paise was captured but " +
                                    "DB write failed and refund attempt also failed — {}",
                            req.getRazorpayPaymentId(), quote.totalPricePaise(), refundEx.getMessage());
                }
            }
            throw dbEx;
        }
    }

    private BookingResponse persist(BookingRequest req, String idempotencyKey, String userId,
                                    CustomerType customerType, ServiceabilityResult serviceability,
                                    int volumetricWeightGrams, int chargeableWeightGrams,
                                    QuoteResult quote, boolean recordPayment) {
        Instant bookedAt = Instant.now();
        String shipmentRef = shipmentRefService.generateRef(req.getOriginCity());

        Shipment shipment = new Shipment();
        shipment.setShipmentRef(shipmentRef);
        shipment.setCustomerType(customerType);
        shipment.setDeliveryType(serviceability.deliveryType());
        shipment.setSenderName(req.getSenderName());
        shipment.setSenderPhone(req.getSenderPhone());
        shipment.setSenderEmail(req.getSenderEmail());
        shipment.setOriginAddress(req.getOriginAddress());
        shipment.setOriginCity(req.getOriginCity().toUpperCase());
        shipment.setOriginPincode(req.getOriginPincode());
        shipment.setReceiverName(req.getReceiverName());
        shipment.setReceiverPhone(req.getReceiverPhone());
        shipment.setReceiverEmail(req.getReceiverEmail());
        shipment.setDestAddress(req.getDestAddress());
        shipment.setDestCity(req.getDestCity().toUpperCase());
        shipment.setDestPincode(req.getDestPincode());
        shipment.setWeightGrams(req.getWeightGrams());
        shipment.setLengthCm(req.getLengthCm());
        shipment.setWidthCm(req.getWidthCm());
        shipment.setHeightCm(req.getHeightCm());
        shipment.setVolumetricWeightGrams(volumetricWeightGrams);
        shipment.setChargeableWeightGrams(chargeableWeightGrams);
        shipment.setDeclaredValuePaise(req.getDeclaredValuePaise());
        shipment.setQuotedPricePaise(quote.baseAmountPaise());
        shipment.setTaxPaise(quote.taxPaise());
        shipment.setTotalPricePaise(quote.totalPricePaise());
        shipment.setRateCardVersion(quote.rateCardVersion());
        shipment.setPickupType(req.getPickupType());
        shipment.setDropType(req.getDropType());
        shipment.setState(ShipmentState.BOOKED);
        shipment.setOriginTileId(serviceability.originTileId());
        shipment.setDestTileId(serviceability.destTileId());
        shipment.setPaymentMode(req.getPaymentMode());
        shipment.setIdempotencyKey(idempotencyKey);
        shipment.setCityId(req.getOriginCity().toUpperCase());
        shipment.setBookedByUserId(UserIds.parse(userId));

        shipment = shipmentRepository.save(shipment);

        if (recordPayment && PaymentMode.PREPAID == req.getPaymentMode()) {
            PaymentTransaction payment = new PaymentTransaction();
            payment.setShipmentId(shipment.getId());
            payment.setRazorpayOrderId(req.getRazorpayOrderId());
            payment.setRazorpayPaymentId(req.getRazorpayPaymentId());
            payment.setRazorpaySignature(req.getRazorpaySignature());
            payment.setAmountPaise(quote.baseAmountPaise());
            payment.setTaxPaise(quote.taxPaise());
            payment.setTotalPaise(quote.totalPricePaise());
            payment.setCurrency("INR");
            payment.setStatus(PaymentStatus.CAPTURED);

            PaymentTransaction savedPayment = paymentTransactionRepository.save(payment);
            shipment.setPaymentId(savedPayment.getId());
        }

        TransitionContext ctx = TransitionContext.fromApi(userId, idempotencyKey);
        historyRepository.save(ShipmentStateHistory.of(shipment.getId(), null, ShipmentState.BOOKED, ctx));

        // ── Best-effort ETA (failure must not roll back the booking) ──────────
        Instant etaPromised = null;
        Integer slaCommitmentMinutes = null;
        try {
            EtaResult etaResult = etaPort.fetchEta(new EtaRequest(
                    shipment.getId(),
                    ShipmentState.BOOKED,
                    bookedAt,
                    new EtaContext(
                            shipment.getOriginCity(),
                            shipment.getDestCity(),
                            serviceability.deliveryType(),
                            bookedAt,
                            null)));
            etaPromised = etaResult.etaPromised();
            slaCommitmentMinutes = etaResult.slaCommitmentMinutes();
            shipment.setEtaPromised(etaPromised);
            shipment.setSlaCommitmentMinutes((short) etaResult.slaCommitmentMinutes());
        } catch (Exception e) {
            log.warn("ETA fetch failed for shipment {}; booking proceeds without ETA: {}",
                    shipment.getId(), e.getMessage());
        }

        // ── Emit CREATED — in-process; ShipmentEventProducer publishes to Kafka AFTER_COMMIT,
        //    so a rolled-back booking never produces a phantom CREATED event. ──────────────
        applicationEventPublisher.publishEvent(new ShipmentBooked(shipment));

        // ── Build response ─────────────────────────────────────────────────────
        BookingResponse.PricingDetails pricing = new BookingResponse.PricingDetails();
        pricing.setQuotedPricePaise(quote.baseAmountPaise());
        pricing.setGstPaise(quote.taxPaise());
        pricing.setTotalPricePaise(quote.totalPricePaise());
        pricing.setCurrency("INR");
        pricing.setBreakdown(quote.breakdown());
        pricing.setRateCardVersion(quote.rateCardVersion());

        BookingResponse.PaymentSummary paymentSummary = new BookingResponse.PaymentSummary();
        paymentSummary.setMode(req.getPaymentMode());
        if (PaymentMode.PREPAID == req.getPaymentMode()) {
            paymentSummary.setStatus(PAYMENT_STATUS_CAPTURED);
            paymentSummary.setRazorpayPaymentId(req.getRazorpayPaymentId());
        } else {
            paymentSummary.setStatus(PAYMENT_STATUS_COD_PENDING);
            paymentSummary.setRazorpayPaymentId(null);
        }

        BookingResponse response = new BookingResponse();
        response.setShipmentRef(shipment.getShipmentRef());
        response.setCustomerType(customerType);
        response.setState(ShipmentState.BOOKED);
        response.setStateLabel(stateMapper.labelFor(ShipmentState.BOOKED));
        response.setDeliveryType(serviceability.deliveryType());
        response.setPricing(pricing);
        response.setEtaPromised(etaPromised);
        response.setSlaCommitmentMinutes(slaCommitmentMinutes);
        response.setTrackingUrl("/api/v1/shipments/mine/" + shipment.getShipmentRef() + "/track");
        response.setParcelId(null);
        response.setLabelStatus("PENDING");
        response.setPayment(paymentSummary);
        return response;
    }

    // ── Helpers: CB + TimeLimiter composition ──────────────────────────────────
    // Use FutureTask (not CompletableFuture) so that supplier exceptions propagate
    // through ExecutionException.getCause() without a CompletionException wrapper.
    // TimeLimiter unwraps ExecutionException and rethrows the original RuntimeException.

    private <T> T callWithTimeout(TimeLimiter tl, CircuitBreaker cb, Supplier<T> supplier) {
        // Explicit Supplier<FutureTask<T>> type forces correct <F,T> inference on decorateFutureSupplier
        java.util.function.Supplier<FutureTask<T>> taskSupplier = () -> {
            FutureTask<T> task = new FutureTask<>(() -> {
                try {
                    return cb.executeSupplier(supplier::get);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            });
            scheduler.submit(task);
            return task;
        };
        Callable<T> callable = tl.decorateFutureSupplier(taskSupplier);
        try {
            return callable.call();
        } catch (TimeoutException e) {
            throw new DownstreamTimeoutException("Downstream call timed out", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void runWithTimeout(TimeLimiter tl, CircuitBreaker cb, Runnable runnable) {
        java.util.function.Supplier<FutureTask<Void>> taskSupplier = () -> {
            FutureTask<Void> task = new FutureTask<>(() -> {
                try {
                    cb.executeRunnable(runnable::run);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
                return null;
            });
            scheduler.submit(task);
            return task;
        };
        Callable<Void> callable = tl.decorateFutureSupplier(taskSupplier);
        try {
            callable.call();
        } catch (TimeoutException e) {
            throw new DownstreamTimeoutException("Downstream call timed out", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
