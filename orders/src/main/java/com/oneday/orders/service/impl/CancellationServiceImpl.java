package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.PaymentMode;
import com.oneday.common.domain.enums.ReturnReason;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.log.AuditLog;
import com.oneday.orders.domain.B2bAccount;
import com.oneday.orders.domain.PaymentTransaction;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.domain.enums.PaymentStatus;
import com.oneday.orders.domain.enums.RefundStatus;
import com.oneday.orders.dto.CancellationResponse;
import com.oneday.orders.dto.CancellationResponse.Disposition;
import com.oneday.orders.dto.CancellationResponse.RefundSummary;
import com.oneday.orders.events.ShipmentCancelled;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.repository.PaymentTransactionRepository;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.B2bBookingService.AccountAccessException;
import com.oneday.orders.service.CancellationPolicy;
import com.oneday.orders.service.CancellationService;
import com.oneday.orders.service.PaymentPort;
import com.oneday.orders.service.ReturnService;
import com.oneday.orders.service.ReturnService.ReturnLane;
import com.oneday.orders.service.ShipmentCustody;
import com.oneday.orders.service.ShipmentStateMachine;
import com.oneday.orders.service.TransitionContext;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * @see CancellationService
 */
@Service
class CancellationServiceImpl implements CancellationService {

    private static final Logger log = LoggerFactory.getLogger(CancellationServiceImpl.class);

    /** Typical Razorpay settlement window communicated to the customer. */
    private static final int REFUND_ESTIMATED_DAYS = 5;

    // ── Mid-transit RTO routing (feature iii; post-sortation gate R4) ─────
    // In-custody states from which a cancel is turned into an RTO instead of a refund. Anything else
    // (pre-custody → refund via the policy; out-for-delivery / DELIVERY_FAILED → existing delivery
    // exception flow; terminal → 409) is not routed here.
    //
    // R4 gate = the dock-receive (hub-scan) boundary, NOT the flight-bag seal. The moment a parcel is
    // scanned into the origin hub it is committed to fly (it will be sorted into a bag and sent onward),
    // so it can no longer be turned around same-city — it flies and RTOs reverse-lane from the dest hub.
    // Only a cancel that arrives BEFORE the hub scan (still pre-hub, in the DA's/van's hands) is same-city.
    //   • RTO_PRE_HUB  → not yet hub-scanned → same-city (resolved at the origin hub on arrival).
    //   • RTO_ORIGIN_HUB ∪ RTO_COMMITTED_TRANSIT → already hub-scanned / in flight → committed; reverse
    //     lane from the dest hub. (Was: consult HubRecallPort and pull from an OPEN bag — retired in R4.)
    //   • RTO_DEST_HUB → at the dest hub → reverse-lane now.
    private static final Set<ShipmentState> RTO_PRE_HUB = EnumSet.of(
            ShipmentState.PICKED_UP, ShipmentState.HANDED_TO_PICKUP_VAN, ShipmentState.RETURNED_TO_HUB);
    private static final Set<ShipmentState> RTO_ORIGIN_HUB = EnumSet.of(
            ShipmentState.AT_ORIGIN_HUB, ShipmentState.ORIGIN_HUB_PROCESSING, ShipmentState.IN_TAKEOFF_BAG);
    private static final Set<ShipmentState> RTO_COMMITTED_TRANSIT = EnumSet.of(
            ShipmentState.DISPATCHED_TO_AIRPORT, ShipmentState.AT_AIRPORT, ShipmentState.DEPARTED,
            ShipmentState.LANDED, ShipmentState.DISPATCHED_TO_HUB);
    private static final Set<ShipmentState> RTO_DEST_HUB = EnumSet.of(
            ShipmentState.AT_DEST_HUB, ShipmentState.DEST_HUB_PROCESSING);

    private final ShipmentRepository shipmentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final B2bAccountRepository b2bAccountRepository;
    private final CancellationPolicy cancellationPolicy;
    private final ShipmentStateMachine stateMachine;
    private final PaymentPort paymentPort;
    private final com.oneday.orders.service.WalletService walletService;
    private final com.oneday.orders.service.OrderService orderService;
    private final ApplicationEventPublisher events;
    private final ReturnService returnService;

    CancellationServiceImpl(ShipmentRepository shipmentRepository,
                            PaymentTransactionRepository paymentTransactionRepository,
                            B2bAccountRepository b2bAccountRepository,
                            CancellationPolicy cancellationPolicy,
                            ShipmentStateMachine stateMachine,
                            PaymentPort paymentPort,
                            com.oneday.orders.service.WalletService walletService,
                            com.oneday.orders.service.OrderService orderService,
                            ApplicationEventPublisher events,
                            ReturnService returnService) {
        this.shipmentRepository = shipmentRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.b2bAccountRepository = b2bAccountRepository;
        this.cancellationPolicy = cancellationPolicy;
        this.stateMachine = stateMachine;
        this.paymentPort = paymentPort;
        this.walletService = walletService;
        this.orderService = orderService;
        this.events = events;
        this.returnService = returnService;
    }

    @Override
    @Transactional
    public CancellationResponse cancel(String shipmentRef, String reason, String userId, boolean b2bLane) {
        Shipment shipment = shipmentRepository.findByShipmentRef(shipmentRef)
                .orElseThrow(() -> new EntityNotFoundException("Shipment not found: " + shipmentRef));

        // Lane guard — a B2C caller must not cancel a B2B shipment (or vice-versa). We 404 rather
        // than 403 so a caller cannot probe for shipments outside its own lane.
        boolean isB2b = shipment.getCustomerType() == CustomerType.B2B;
        if (isB2b != b2bLane) {
            throw new EntityNotFoundException("Shipment not found: " + shipmentRef);
        }

        // Ownership guard — the caller must have booked this shipment. Fail CLOSED: a null booker
        // must not bypass the check (that would let any authenticated user cancel it). 404 (not 403)
        // so a caller cannot probe for shipments owned by other users (same reasoning as the lane guard).
        if (shipment.getBookedByUserId() == null
                || !shipment.getBookedByUserId().toString().equals(userId)) {
            throw new EntityNotFoundException("Shipment not found: " + shipmentRef);
        }

        return doCancel(shipment, reason, userId, false);
    }

    @Override
    @Transactional
    public CancellationResponse cancelAsAdmin(String shipmentRef, String reason, String userId) {
        // No lane guard (admin cancels any lane) and no ownership guard — admin acts on behalf of any user.
        Shipment shipment = shipmentRepository.findByShipmentRef(shipmentRef)
                .orElseThrow(() -> new EntityNotFoundException("Shipment not found: " + shipmentRef));
        return doCancel(shipment, reason, userId, true);
    }

    @Override
    @Transactional
    public CancellationResponse cancelAsStationManager(String shipmentRef, String reason, String userId,
                                                        String cityScope) {
        Shipment shipment = shipmentRepository.findByShipmentRef(shipmentRef)
                .orElseThrow(() -> new EntityNotFoundException("Shipment not found: " + shipmentRef));

        String custodyCity = ShipmentCustody.custodian(shipment.getState()) == ShipmentCustody.Custodian.ORIGIN
                ? shipment.getOriginCity()
                : shipment.getDestCity();
        if (!cityScope.equals(custodyCity)) {
            // Not this station's parcel to act on right now — 404, not 403, same reasoning as the
            // b2b/b2c lane guard above.
            throw new EntityNotFoundException("Shipment not found: " + shipmentRef);
        }

        return doCancel(shipment, reason, userId, true);
    }

    @Override
    @Transactional
    public CancellationResponse initiateRtoAsAdmin(String shipmentRef, String reason, String userId) {
        Shipment shipment = shipmentRepository.findByShipmentRef(shipmentRef)
                .orElseThrow(() -> new EntityNotFoundException("Shipment not found: " + shipmentRef));
        return routeToRto(shipment, reason, userId);
    }

    @Override
    @Transactional
    public CancellationResponse initiateRtoAsStationManager(String shipmentRef, String reason,
                                                            String userId, String cityScope) {
        Shipment shipment = shipmentRepository.findByShipmentRef(shipmentRef)
                .orElseThrow(() -> new EntityNotFoundException("Shipment not found: " + shipmentRef));

        String custodyCity = ShipmentCustody.custodian(shipment.getState()) == ShipmentCustody.Custodian.ORIGIN
                ? shipment.getOriginCity()
                : shipment.getDestCity();
        if (!cityScope.equals(custodyCity)) {
            // Not this station's parcel to act on — 404, not 403 (same reasoning as the cancel guards).
            throw new EntityNotFoundException("Shipment not found: " + shipmentRef);
        }
        return routeToRto(shipment, reason, userId);
    }

    private CancellationResponse doCancel(Shipment shipment, String reason, String userId,
                                          boolean bypassOwnership) {
        String shipmentRef = shipment.getShipmentRef();
        boolean isB2b = shipment.getCustomerType() == CustomerType.B2B;

        // Not yet in custody → refund/cancel (below). In custody → turn the cancel into an RTO: the
        // goods are already ours, so we send them back to the sender rather than just refunding.
        if (!cancellationPolicy.isCancellable(shipment.getState(), shipment.getPickupType())) {
            return routeToRto(shipment, reason, userId);
        }

        ShipmentState cancelledAtState = shipment.getState();

        // ── Reverse payment / credit before transitioning ────────────────────────
        RefundSummary refund;
        boolean refundInitiated;
        Long refundAmountPaise;
        if (isB2b) {
            reverseB2bCredit(shipment, userId, bypassOwnership);
            refund = null;
            refundInitiated = false;
            refundAmountPaise = null;
        } else if (shipment.getPaymentMode() == PaymentMode.PREPAID) {
            refund = refundPrepaid(shipment);
            refundInitiated = refund != null && "REFUND_INITIATED".equals(refund.status());
            refundAmountPaise = refund != null ? refund.refundAmountPaise() : null;
        } else {
            // COD — nothing was collected, nothing to refund.
            refund = null;
            refundInitiated = false;
            refundAmountPaise = null;
        }

        // ── Transition to CANCELLED (locks the row, appends state history, fires the
        //    plain → CANCELLED STATE_CHANGED event) ───────────────────────────────
        stateMachine.transition(shipment.getId(), ShipmentState.CANCELLED,
                TransitionContext.fromApi(userId, shipmentRef).withNotes(reason));

        shipment.setCancelledAt(Instant.now());
        shipment.setCancellationReason(reason);
        shipmentRepository.save(shipment);

        // Back the cancelled shipment out of its parent order's rollup so parcel_count / total always
        // reflect the live children (order-repair "remove" is just a cancel from the order's view). Same
        // transaction as the cancel — count/total can never drift from the shipments that remain.
        if (shipment.getOrderId() != null) {
            orderService.removeShipment(shipment.getOrderId(), shipment.getTotalPricePaise());
        }

        // Rich CANCELLED event (reason + refund) — mapped to Kafka AFTER_COMMIT.
        events.publishEvent(new ShipmentCancelled(
                shipment.getId(), shipmentRef, cancelledAtState, reason,
                refundInitiated, refundAmountPaise, Instant.now()));

        AuditLog.event("shipment.cancelled")
                .kv("shipmentId", shipment.getId())
                .kv("shipmentRef", shipmentRef)
                .kv("cancelledAtState", cancelledAtState)
                .kv("lane", isB2b ? "B2B" : "B2C")
                .kv("refundInitiated", refundInitiated)
                .kv("refundAmountPaise", refundAmountPaise)
                .log();

        log.info("Cancelled shipment {} at state {} (refundInitiated={})",
                shipmentRef, cancelledAtState, refundInitiated);
        return new CancellationResponse(shipmentRef, ShipmentState.CANCELLED, refund);
    }

    /**
     * In-custody cancel → RTO (feature iii; R4 dock-receive gate). Not-yet-in-custody shipments never
     * reach here (the policy refunds them). The return is fired now only when the parcel is already at
     * the destination hub; otherwise the intent is scheduled and fires when the parcel next reaches a
     * hub — same-city at the origin hub (cancel arrived before the hub scan) or reverse-lane at the dest
     * hub (cancel arrived once hub-scanned / in flight). Return children, already-returning originals,
     * out-for-delivery and terminal states are not routed here.
     */
    private CancellationResponse routeToRto(Shipment shipment, String reason, String userId) {
        ShipmentState state = shipment.getState();
        String ref = shipment.getShipmentRef();

        // A return child can't be returned again (the doorstep flow holds an exhausted child at the hub).
        if (shipment.getReturnOfShipmentId() != null) {
            throw new CancellationNotAllowedException(
                    "Shipment " + ref + " is a return — it cannot be returned again");
        }
        // Already returning (e.g. a repeat click) — report the existing child, don't mint another.
        if (shipment.getReturnShipmentId() != null) {
            Shipment child = shipmentRepository.findById(shipment.getReturnShipmentId()).orElse(null);
            return new CancellationResponse(ref, state, null, Disposition.RETURN_INITIATED,
                    child != null ? child.getShipmentRef() : null);
        }

        // The RTO intent (who/why/when) is recorded on the shipment row. For the *immediate* branch it
        // stamps inside initiateReturn (on the instance it locks and transitions — open-in-view is off, so
        // `shipment` here is a different persistence-context copy we must not re-save; see resolveNow). For
        // the *deferred* branches it happens in scheduleDeferred, which owns and saves this same instance.

        // Already at the destination hub → reverse-lane return now.
        if (RTO_DEST_HUB.contains(state)) {
            return resolveNow(shipment, reason, userId, ReturnLane.REVERSE_FROM_DEST);
        }

        // Pre-hub, in hand: the cancel arrived before the hub scan → defer; RtoIntentResolver fires a
        // same-city return the moment the parcel is dock-received at the origin hub (a transition INTO
        // AT_ORIGIN_HUB), and HubReceivingService skips the outbound sort so it never flies.
        if (RTO_PRE_HUB.contains(state)) {
            return scheduleDeferred(shipment, reason, userId, ReturnLane.SAME_CITY_FROM_ORIGIN,
                    "resolves same-city when the parcel is received at the origin hub");
        }

        // Already hub-scanned (origin hub onward) or in flight → committed to fly (R4: no bag-pull).
        // Defer; the resolver fires a reverse-lane return when the parcel reaches the destination hub.
        if (RTO_ORIGIN_HUB.contains(state) || RTO_COMMITTED_TRANSIT.contains(state)) {
            return scheduleDeferred(shipment, reason, userId, ReturnLane.REVERSE_FROM_DEST,
                    "already hub-scanned — flies forward and returns from the destination hub");
        }

        // Out-for-delivery / DELIVERY_FAILED / terminal — the doorstep RTO + delivery-exception flows
        // own those; a mid-transit cancel does not apply.
        throw new CancellationNotAllowedException(
                "Shipment " + ref + " cannot be returned from state " + state);
    }

    /** Spawn the return child immediately and mark the intent resolved (dest-hub reverse lane only). */
    private CancellationResponse resolveNow(Shipment shipment, String reason, String userId,
                                            ReturnLane lane) {
        // NB: open-in-view is off, so initiateReturn runs in its own persistence context — the
        // `shipment` instance here is a *different* managed copy than the one it locks and transitions
        // to RTO_INITIATED. Re-saving this stale copy afterwards would clobber that transition back to
        // its old state, so we must NOT mutate/save it here. initiateReturn stamps rto_requested_at /
        // rto_resolved_at on the locked instance itself (it's a POST_CUSTODY_CANCEL return).
        ReturnService.ReturnResult result = returnService.initiateReturn(
                shipment.getId(), ReturnReason.POST_CUSTODY_CANCEL, lane,
                TransitionContext.fromApi(userId, shipment.getShipmentRef()).withNotes(reason));
        AuditLog.event("shipment.rto_from_cancel")
                .kv("shipmentRef", shipment.getShipmentRef())
                .kv("resolution", "IMMEDIATE")
                .kv("lane", lane.name())
                .kv("returnChildRef", result.childShipmentRef())
                .log();
        log.info("In-custody cancel of {} → return child {} ({}, immediate)",
                shipment.getShipmentRef(), result.childShipmentRef(), lane);
        return new CancellationResponse(shipment.getShipmentRef(), ShipmentState.RTO_INITIATED, null,
                Disposition.RETURN_INITIATED, result.childShipmentRef());
    }

    /** Record the intent + its lane; {@code RtoIntentResolver}/{@code RtoIntentReconcileJob} fire the
     *  return on that lane at the parcel's resolution hub. */
    private CancellationResponse scheduleDeferred(Shipment shipment, String reason, String userId,
                                                  ReturnLane lane, String why) {
        // Deferred: stamp the intent on this same (owned) instance and save it. No transition competes
        // here, so this is safe. rto_resolved_at stays null until the resolver mints the child at the hub.
        shipment.setRtoRequestedAt(Instant.now());
        shipment.setRtoRequestedBy(userId != null && userId.length() <= 64 ? userId : null);
        shipment.setRtoReason(clampReason(reason));
        shipment.setRtoLane(lane.name());
        shipmentRepository.save(shipment);
        AuditLog.event("shipment.rto_from_cancel")
                .kv("shipmentRef", shipment.getShipmentRef())
                .kv("resolution", "DEFERRED")
                .kv("atState", shipment.getState())
                .log();
        log.info("In-custody cancel of {} at {} → RTO scheduled ({})",
                shipment.getShipmentRef(), shipment.getState(), why);
        return new CancellationResponse(shipment.getShipmentRef(), shipment.getState(), null,
                Disposition.RETURN_SCHEDULED, null);
    }

    /** {@code shipments.rto_reason} is VARCHAR(500); clamp so an oversized reason can't fail the tx. */
    static String clampReason(String reason) {
        if (reason == null || reason.length() <= 500) {
            return reason;
        }
        return reason.substring(0, 500);
    }

    /** B2B: reverse the shipping charge — refund the wallet, or decrement outstanding credit. */
    private void reverseB2bCredit(Shipment shipment, String userId, boolean bypassOwnership) {
        B2bAccount account = b2bAccountRepository.findByIdForUpdate(shipment.getB2bAccountId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "B2B account not found: " + shipment.getB2bAccountId()));

        // Admin (bypassOwnership) cancels on behalf of the account; customers must own it. Fail
        // CLOSED — a null owner must not let a non-owning B2B user reverse credit on this account.
        if (!bypassOwnership
                && (account.getOwnerUserId() == null
                    || !account.getOwnerUserId().toString().equals(userId))) {
            throw new AccountAccessException(
                    "User " + userId + " does not own account " + account.getId());
        }

        if (shipment.getFundingSource() == com.oneday.orders.domain.FundingSource.WALLET) {
            walletService.refundForCancellation(account, shipment.getTotalPricePaise(),
                    shipment.getShipmentRef(), UserIds.parse(userId));
        } else {
            long reversed = Math.max(0L, account.getOutstandingBalancePaise() - shipment.getTotalPricePaise());
            account.setOutstandingBalancePaise(reversed);
        }
    }

    /**
     * PREPAID: initiate a Razorpay refund for the captured payment. Per design §15.7 / OD-7, a
     * Razorpay refund failure does NOT block the cancellation — the parcel is still cancelled and
     * the refund is flagged {@code FAILED} for manual ops follow-up.
     */
    private RefundSummary refundPrepaid(Shipment shipment) {
        PaymentTransaction tx = paymentTransactionRepository.findByShipmentId(shipment.getId()).stream()
                .filter(t -> t.getStatus() == PaymentStatus.CAPTURED && t.getRazorpayPaymentId() != null)
                .findFirst()
                .orElse(null);

        if (tx == null) {
            // No captured payment on record — nothing to refund (e.g. payment never completed).
            log.warn("PREPAID shipment {} has no captured payment transaction; skipping refund",
                    shipment.getShipmentRef());
            return null;
        }

        long amount = shipment.getTotalPricePaise();
        try {
            String refundId = paymentPort.initiateRefund(tx.getRazorpayPaymentId(), amount);
            tx.setStatus(PaymentStatus.REFUND_INITIATED);
            tx.setRefundStatus(RefundStatus.PENDING);
            tx.setRefundId(refundId);
            tx.setRefundAmountPaise(amount);
            return new RefundSummary("REFUND_INITIATED", REFUND_ESTIMATED_DAYS, amount, refundId);
        } catch (PaymentPort.PaymentRefundException e) {
            // Cancel anyway; flag for ops. (OD-7: manual ops + alert in v1.)
            log.error("Razorpay refund FAILED for shipment {} (payment {}): {} — flagged for ops",
                    shipment.getShipmentRef(), tx.getRazorpayPaymentId(), e.getMessage());
            tx.setRefundStatus(RefundStatus.FAILED);
            tx.setRefundAmountPaise(amount);
            return new RefundSummary("REFUND_FAILED", null, amount, null);
        }
    }
}
