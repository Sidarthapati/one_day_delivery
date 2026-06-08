package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.CustomerType;
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
import com.oneday.orders.domain.B2bAccount;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.domain.ShipmentStateHistory;
import com.oneday.orders.dto.B2bBookingRequest;
import com.oneday.orders.dto.BookingResponse;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.repository.ShipmentStateHistoryRepository;
import com.oneday.orders.service.B2bBookingService;
import com.oneday.orders.service.BookingService;
import com.oneday.orders.service.CustomerVisibleStateMapper;
import com.oneday.orders.service.ShipmentRefService;
import com.oneday.orders.service.TransitionContext;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@Service
class B2bBookingServiceImpl implements B2bBookingService {

    private static final Logger log = LoggerFactory.getLogger(B2bBookingServiceImpl.class);

    private final B2bAccountRepository b2bAccountRepository;
    private final ServiceabilityPort serviceabilityPort;
    private final PricingPort pricingPort;
    private final EtaPort etaPort;
    private final ShipmentRefService shipmentRefService;
    private final ShipmentRepository shipmentRepository;
    private final ShipmentStateHistoryRepository historyRepository;
    private final CustomerVisibleStateMapper stateMapper;
    private final TransactionTemplate tx;

    private final CircuitBreaker serviceabilityCb;
    private final CircuitBreaker pricingCb;

    private final TimeLimiter serviceabilityTl;
    private final TimeLimiter pricingTl;

    private final ScheduledExecutorService scheduler;

    B2bBookingServiceImpl(B2bAccountRepository b2bAccountRepository,
                          ServiceabilityPort serviceabilityPort,
                          PricingPort pricingPort,
                          EtaPort etaPort,
                          ShipmentRefService shipmentRefService,
                          ShipmentRepository shipmentRepository,
                          ShipmentStateHistoryRepository historyRepository,
                          CustomerVisibleStateMapper stateMapper,
                          TransactionTemplate transactionTemplate,
                          CircuitBreakerRegistry circuitBreakerRegistry,
                          TimeLimiterRegistry timeLimiterRegistry,
                          @Qualifier("resilienceScheduler") ScheduledExecutorService resilienceScheduler) {
        this.b2bAccountRepository = b2bAccountRepository;
        this.serviceabilityPort   = serviceabilityPort;
        this.pricingPort          = pricingPort;
        this.etaPort              = etaPort;
        this.shipmentRefService   = shipmentRefService;
        this.shipmentRepository   = shipmentRepository;
        this.historyRepository    = historyRepository;
        this.stateMapper          = stateMapper;
        this.tx                   = transactionTemplate;
        this.serviceabilityCb     = circuitBreakerRegistry.circuitBreaker("serviceability");
        this.pricingCb            = circuitBreakerRegistry.circuitBreaker("pricing");
        this.serviceabilityTl     = timeLimiterRegistry.timeLimiter("serviceability");
        this.pricingTl            = timeLimiterRegistry.timeLimiter("pricing");
        this.scheduler            = resilienceScheduler;
    }

    @Override
    public BookingResponse book(B2bBookingRequest req, String idempotencyKey, String userId) {
        // ── 1. Fetch account (outside TX) ─────────────────────────────────────
        B2bAccount account = b2bAccountRepository.findById(req.getB2bAccountId())
                .orElseThrow(() -> new AccountNotFoundException(
                        "B2B account not found: " + req.getB2bAccountId()));
        if (!Boolean.TRUE.equals(account.getIsActive())) {
            throw new AccountInactiveException(
                    "B2B account is inactive: " + req.getB2bAccountId());
        }
        // Ownership: the caller must own the account (when an owner is set). Prevents one
        // B2B user drawing down another account's credit. ADMIN-on-behalf is not modelled yet.
        if (account.getOwnerUserId() != null && !account.getOwnerUserId().toString().equals(userId)) {
            throw new AccountAccessException(
                    "Caller is not authorized for B2B account: " + req.getB2bAccountId());
        }

        // ── 2. Serviceability (outside TX) ────────────────────────────────────
        ServiceabilityResult serviceability = callWithTimeout(serviceabilityTl, serviceabilityCb,
                () -> serviceabilityPort.check(new ServiceabilityQuery(
                        req.getOriginPincode(), req.getDestPincode(),
                        req.getOriginAddress().getLatitude(), req.getOriginAddress().getLongitude(),
                        req.getDestAddress().getLatitude(), req.getDestAddress().getLongitude())));
        if (!serviceability.serviceable()) {
            throw new BookingService.ServiceabilityException(
                    "Route not serviceable: " + req.getOriginPincode() + " → " + req.getDestPincode());
        }

        // ── 3. Weight calculation ──────────────────────────────────────────────
        int volumetricWeightGrams = (req.getLengthCm() * req.getWidthCm() * req.getHeightCm()) / 5;
        int chargeableWeightGrams = Math.max(req.getWeightGrams(), volumetricWeightGrams);

        // ── 4. Pricing with account-specific rate card (outside TX) ───────────
        QuoteResult quote = callWithTimeout(pricingTl, pricingCb, () -> pricingPort.computeQuote(
                new QuoteRequest(
                        CustomerType.B2B,
                        serviceability.deliveryType(),
                        req.getOriginCity().toUpperCase(),
                        req.getDestCity().toUpperCase(),
                        chargeableWeightGrams,
                        req.getDeclaredValuePaise(),
                        account.getRateCardId())));

        // ── 5. DB transaction: credit check → persist → balance increment ──────
        final int finalVolumetric = volumetricWeightGrams;
        final int finalChargeable = chargeableWeightGrams;
        return tx.execute(status ->
                persistB2b(req, idempotencyKey, userId, account, serviceability,
                        finalVolumetric, finalChargeable, quote));
    }

    private BookingResponse persistB2b(B2bBookingRequest req, String idempotencyKey, String userId,
                                       B2bAccount accountSnapshot, ServiceabilityResult serviceability,
                                       int volumetricWeightGrams, int chargeableWeightGrams,
                                       QuoteResult quote) {
        Instant bookedAt = Instant.now();

        // ── 5a. SELECT FOR UPDATE — re-fetch account inside TX ─────────────────
        B2bAccount account = b2bAccountRepository.findByIdForUpdate(req.getB2bAccountId())
                .orElseThrow(() -> new AccountNotFoundException(
                        "B2B account not found inside transaction: " + req.getB2bAccountId()));

        // ── 5b. Pessimistic credit check ───────────────────────────────────────
        long newOutstanding = account.getOutstandingBalancePaise() + quote.totalPricePaise();
        if (newOutstanding > account.getCreditLimitPaise()) {
            throw new CreditLimitExceededException(
                    "Credit limit exceeded for account " + req.getB2bAccountId() +
                    ": outstanding " + account.getOutstandingBalancePaise() +
                    " + booking " + quote.totalPricePaise() +
                    " > limit " + account.getCreditLimitPaise());
        }

        // ── 5c. Persist Shipment ───────────────────────────────────────────────
        String shipmentRef = shipmentRefService.generateRef(req.getOriginCity());

        Shipment shipment = new Shipment();
        shipment.setShipmentRef(shipmentRef);
        shipment.setCustomerType(CustomerType.B2B);
        shipment.setB2bAccountId(req.getB2bAccountId());
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
        shipment.setPaymentMode(null);   // B2B: credit; no payment mode column value
        shipment.setIdempotencyKey(idempotencyKey);
        shipment.setCityId(req.getOriginCity().toUpperCase());
        shipment.setBookedByUserId(UserIds.parse(userId));

        shipment = shipmentRepository.save(shipment);

        // ── 5d. Increment outstanding balance ──────────────────────────────────
        account.setOutstandingBalancePaise(newOutstanding);
        b2bAccountRepository.save(account);

        // ── 5e. State history ──────────────────────────────────────────────────
        TransitionContext ctx = TransitionContext.fromApi(userId, idempotencyKey);
        historyRepository.save(ShipmentStateHistory.of(shipment.getId(), null, ShipmentState.BOOKED, ctx));

        // ── 5f. Best-effort ETA (failure must not roll back the booking) ───────
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
            log.warn("ETA fetch failed for B2B shipment {}; booking proceeds without ETA: {}",
                    shipment.getId(), e.getMessage());
        }

        // ── 6. Build response (payment = null for B2B) ─────────────────────────
        BookingResponse.PricingDetails pricing = new BookingResponse.PricingDetails();
        pricing.setQuotedPricePaise(quote.baseAmountPaise());
        pricing.setGstPaise(quote.taxPaise());
        pricing.setTotalPricePaise(quote.totalPricePaise());
        pricing.setCurrency("INR");
        pricing.setBreakdown(quote.breakdown());
        pricing.setRateCardVersion(quote.rateCardVersion());

        BookingResponse response = new BookingResponse();
        response.setShipmentRef(shipment.getShipmentRef());
        response.setCustomerType(CustomerType.B2B);
        response.setState(ShipmentState.BOOKED);
        response.setStateLabel(stateMapper.labelFor(ShipmentState.BOOKED));
        response.setDeliveryType(serviceability.deliveryType());
        response.setPricing(pricing);
        response.setEtaPromised(etaPromised);
        response.setSlaCommitmentMinutes(slaCommitmentMinutes);
        response.setTrackingUrl("/api/v1/shipments/" + shipment.getShipmentRef() + "/track");
        response.setParcelId(null);
        response.setLabelStatus("PENDING");
        response.setPayment(null);  // omitted from JSON via @JsonInclude(NON_NULL) on the field
        return response;
    }

    // ── Helpers: CB + TimeLimiter composition ──────────────────────────────────
    // FutureTask (not CompletableFuture) so supplier exceptions propagate without
    // a CompletionException wrapper. TimeLimiter unwraps ExecutionException and
    // rethrows the original RuntimeException.

    private <T> T callWithTimeout(TimeLimiter tl, CircuitBreaker cb, Supplier<T> supplier) {
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
            throw new BookingService.DownstreamTimeoutException("Downstream call timed out", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
