package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.DropType;
import com.oneday.common.domain.enums.PaymentMode;
import com.oneday.common.domain.enums.PickupType;
import com.oneday.common.domain.enums.ReturnReason;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.log.AuditLog;
import com.oneday.common.port.PricingPort;
import com.oneday.common.port.ServiceabilityPort;
import com.oneday.common.port.dto.QuoteRequest;
import com.oneday.common.port.dto.QuoteResult;
import com.oneday.common.port.dto.ServiceabilityQuery;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * Mints and bills a return child shipment ({@code <ref>_R}) for an undeliverable original. See
 * {@link ReturnService}. Re-entry into the physical pipeline is the hub dock-receive of the child ref
 * (the parcel already sits at that hub after the RETURN_TO_HUB carry-back) — every mover downstream
 * reads origin/dest straight off the child row, which now names the reversed lane, so no movement
 * code changes. The automatic scan-independent AT_ORIGIN_HUB trigger (hub {@code ShipmentStateConsumer})
 * and reverse-lane flight seeding are the two documented follow-ups for fully-automatic booted e2e.
 */
@Service
class ReturnServiceImpl implements ReturnService {

    private static final Logger log = LoggerFactory.getLogger(ReturnServiceImpl.class);

    private final ShipmentRepository shipmentRepository;
    private final ShipmentStateHistoryRepository historyRepository;
    private final B2bAccountRepository b2bAccountRepository;
    private final OrderService orderService;
    private final ServiceabilityPort serviceabilityPort;
    private final PricingPort pricingPort;
    private final ShipmentStateMachine stateMachine;

    ReturnServiceImpl(ShipmentRepository shipmentRepository,
                      ShipmentStateHistoryRepository historyRepository,
                      B2bAccountRepository b2bAccountRepository,
                      OrderService orderService,
                      ServiceabilityPort serviceabilityPort,
                      PricingPort pricingPort,
                      ShipmentStateMachine stateMachine) {
        this.shipmentRepository = shipmentRepository;
        this.historyRepository = historyRepository;
        this.b2bAccountRepository = b2bAccountRepository;
        this.orderService = orderService;
        this.serviceabilityPort = serviceabilityPort;
        this.pricingPort = pricingPort;
        this.stateMachine = stateMachine;
    }

    @Override
    @Transactional
    public ReturnResult initiateReturn(UUID originalShipmentId, ReturnReason reason, TransitionContext ctx) {
        // Lock the original for the whole tx so concurrent RTO_INITIATED calls serialize on it — the
        // idempotency check below then can't be raced into a duplicate-child uniqueness error.
        Shipment original = shipmentRepository.findByIdWithLock(originalShipmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Shipment not found: " + originalShipmentId));

        // Idempotent: one live return child per original. A repeat call returns the existing child.
        Shipment existing = original.getReturnShipmentId() != null
                ? shipmentRepository.findById(original.getReturnShipmentId()).orElse(null)
                : shipmentRepository.findByReturnOfShipmentId(originalShipmentId).orElse(null);
        if (existing != null) {
            return new ReturnResult(existing.getId(), existing.getShipmentRef(), originalShipmentId);
        }

        // Reverse the geography and re-resolve serviceability for the return lane (dest → sender).
        ServiceabilityResult sr = serviceabilityPort.check(new ServiceabilityQuery(
                original.getDestPincode(), original.getOriginPincode(),
                latOf(original.getDestAddress()), lonOf(original.getDestAddress()),
                latOf(original.getOriginAddress()), lonOf(original.getOriginAddress())));
        if (!sr.serviceable()) {
            // A return whose sender area is no longer serviceable is rare; proceed best-effort with
            // whatever tiles resolved so the parcel still leaves the hub — ops handles the tail.
            log.warn("Return lane {}→{} not serviceable for original {} — proceeding best-effort",
                    original.getDestCity(), original.getOriginCity(), originalShipmentId);
        }

        boolean b2b = original.getCustomerType() == CustomerType.B2B && original.getB2bAccountId() != null;
        B2bAccount account = b2b
                ? b2bAccountRepository.findByIdForUpdate(original.getB2bAccountId()).orElse(null)
                : null;
        UUID rateCardId = account != null ? account.getRateCardId() : null;
        PaymentMode childPaymentMode = b2b ? null : PaymentMode.PREPAID; // no COD ever on a return

        int chargeableGrams = original.getChargeableWeightGrams() != null
                ? original.getChargeableWeightGrams()
                : (original.getWeightGrams() != null ? original.getWeightGrams() : 0);

        QuoteResult quote = pricingPort.computeQuote(new QuoteRequest(
                original.getCustomerType(), sr.deliveryType(),
                original.getDestCity(), original.getOriginCity(),
                chargeableGrams, original.getDeclaredValuePaise(), rateCardId, childPaymentMode));

        Shipment child = mintChild(original, sr, quote, childPaymentMode);
        shipmentRepository.save(child);

        // Roll the return into the same parcel order (child under the same order_id).
        if (original.getOrderId() != null) {
            orderService.addShipment(original.getOrderId(), quote.totalPricePaise());
        }

        // Bill B2B at spawn — a return must ship, so NO credit-limit check (proceed past the limit).
        if (account != null) {
            long outstanding = account.getOutstandingBalancePaise() == null ? 0L : account.getOutstandingBalancePaise();
            account.setOutstandingBalancePaise(outstanding + quote.totalPricePaise());
            b2bAccountRepository.save(account);
        }

        // Birth history null → AT_ORIGIN_HUB (parcel already in custody; NO ShipmentBooked/CREATED event,
        // so M5 never books a phantom pickup for the child).
        historyRepository.save(ShipmentStateHistory.of(child.getId(), null, ShipmentState.AT_ORIGIN_HUB, ctx));

        // Link both directions and mark the original as returning. The child is delivered → the
        // completion listener drives the original RTO_INITIATED → RTO_COMPLETED.
        original.setReturnShipmentId(child.getId());
        shipmentRepository.save(original);
        stateMachine.transition(originalShipmentId, ShipmentState.RTO_INITIATED, ctx);

        AuditLog.event("return.initiated")
                .kv("originalShipmentId", originalShipmentId)
                .kv("childShipmentId", child.getId())
                .kv("childShipmentRef", child.getShipmentRef())
                .kv("reason", reason.name())
                .kv("billedPaise", account != null ? quote.totalPricePaise() : 0L)
                .log();

        return new ReturnResult(child.getId(), child.getShipmentRef(), originalShipmentId);
    }

    /** Build the reversed child shipment, born at the origin hub. */
    private Shipment mintChild(Shipment original, ServiceabilityResult sr, QuoteResult quote,
                               PaymentMode childPaymentMode) {
        Shipment c = new Shipment();
        c.setShipmentRef(original.getShipmentRef() + "_R");
        c.setReturnOfShipmentId(original.getId());
        c.setOrderId(original.getOrderId());
        c.setCustomerType(original.getCustomerType());
        c.setDeliveryType(sr.deliveryType());
        c.setB2bAccountId(original.getB2bAccountId());
        c.setCategoryId(original.getCategoryId());
        c.setBookedByUserId(original.getBookedByUserId());

        // Reversed geography: return origin = original dest; return dest = original sender.
        c.setSenderName(original.getReceiverName());
        c.setSenderPhone(original.getReceiverPhone());
        c.setSenderEmail(original.getReceiverEmail());
        c.setOriginAddress(original.getDestAddress());
        c.setOriginCity(original.getDestCity());
        c.setOriginPincode(original.getDestPincode());
        c.setReceiverName(original.getSenderName());
        c.setReceiverPhone(original.getSenderPhone());
        c.setReceiverEmail(original.getSenderEmail());
        c.setDestAddress(original.getOriginAddress());
        c.setDestCity(original.getOriginCity());
        c.setDestPincode(original.getOriginPincode());
        c.setCityId(original.getDestCity());   // new origin city drives auth scoping

        // Same physical parcel — carry the dimensions over verbatim.
        c.setWeightGrams(original.getWeightGrams());
        c.setLengthCm(original.getLengthCm());
        c.setWidthCm(original.getWidthCm());
        c.setHeightCm(original.getHeightCm());
        c.setVolumetricWeightGrams(original.getVolumetricWeightGrams());
        c.setChargeableWeightGrams(original.getChargeableWeightGrams());

        // Reverse-lane pricing.
        c.setDeclaredValuePaise(original.getDeclaredValuePaise());
        c.setQuotedPricePaise(quote.totalPricePaise() - quote.taxPaise());
        c.setTaxPaise(quote.taxPaise());
        c.setTotalPricePaise(quote.totalPricePaise());
        c.setFinalPricePaise(quote.totalPricePaise());
        c.setRateCardVersion(quote.rateCardVersion());

        // Born at the hub: no pickup; delivered to the sender's door.
        c.setPickupType(PickupType.SELF_DROP);
        c.setDropType(DropType.DA_DELIVERY);
        c.setState(ShipmentState.AT_ORIGIN_HUB);
        c.setSlaCommitmentMinutes(original.getSlaCommitmentMinutes());

        c.setOriginTileId(sr.originTileId());
        c.setDestTileId(sr.destTileId());
        c.setPaymentMode(childPaymentMode);
        c.setFundingSource(original.getFundingSource());
        c.setTrackToken(UUID.randomUUID().toString().replace("-", ""));
        return c;
    }

    private static Double latOf(Address a) {
        return a != null ? a.getLatitude() : null;
    }

    private static Double lonOf(Address a) {
        return a != null ? a.getLongitude() : null;
    }
}
