package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.PaymentMode;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.B2bAccount;
import com.oneday.orders.domain.PaymentTransaction;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.domain.enums.PaymentStatus;
import com.oneday.orders.domain.enums.RefundStatus;
import com.oneday.orders.dto.CancellationResponse;
import com.oneday.orders.dto.CancellationResponse.RefundSummary;
import com.oneday.orders.events.ShipmentCancelled;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.repository.PaymentTransactionRepository;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.B2bBookingService.AccountAccessException;
import com.oneday.orders.service.CancellationPolicy;
import com.oneday.orders.service.CancellationService;
import com.oneday.orders.service.PaymentPort;
import com.oneday.orders.service.ShipmentStateMachine;
import com.oneday.orders.service.TransitionContext;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * @see CancellationService
 */
@Service
class CancellationServiceImpl implements CancellationService {

    private static final Logger log = LoggerFactory.getLogger(CancellationServiceImpl.class);

    /** Typical Razorpay settlement window communicated to the customer. */
    private static final int REFUND_ESTIMATED_DAYS = 5;

    private final ShipmentRepository shipmentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final B2bAccountRepository b2bAccountRepository;
    private final CancellationPolicy cancellationPolicy;
    private final ShipmentStateMachine stateMachine;
    private final PaymentPort paymentPort;
    private final ApplicationEventPublisher events;

    CancellationServiceImpl(ShipmentRepository shipmentRepository,
                            PaymentTransactionRepository paymentTransactionRepository,
                            B2bAccountRepository b2bAccountRepository,
                            CancellationPolicy cancellationPolicy,
                            ShipmentStateMachine stateMachine,
                            PaymentPort paymentPort,
                            ApplicationEventPublisher events) {
        this.shipmentRepository = shipmentRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.b2bAccountRepository = b2bAccountRepository;
        this.cancellationPolicy = cancellationPolicy;
        this.stateMachine = stateMachine;
        this.paymentPort = paymentPort;
        this.events = events;
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

        if (!cancellationPolicy.isCancellable(shipment.getState(), shipment.getPickupType())) {
            throw new CancellationNotAllowedException(
                    "Shipment " + shipmentRef + " can no longer be cancelled in state " + shipment.getState());
        }

        ShipmentState cancelledAtState = shipment.getState();

        // ── Reverse payment / credit before transitioning ────────────────────────
        RefundSummary refund;
        boolean refundInitiated;
        Long refundAmountPaise;
        if (isB2b) {
            reverseB2bCredit(shipment, userId);
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

        // Rich CANCELLED event (reason + refund) — mapped to Kafka AFTER_COMMIT.
        events.publishEvent(new ShipmentCancelled(
                shipment.getId(), shipmentRef, cancelledAtState, reason,
                refundInitiated, refundAmountPaise, Instant.now()));

        log.info("Cancelled shipment {} at state {} (refundInitiated={})",
                shipmentRef, cancelledAtState, refundInitiated);
        return new CancellationResponse(shipmentRef, ShipmentState.CANCELLED, refund);
    }

    /** B2B: decrement the account's outstanding balance by this shipment's total (credit reversal). */
    private void reverseB2bCredit(Shipment shipment, String userId) {
        B2bAccount account = b2bAccountRepository.findByIdForUpdate(shipment.getB2bAccountId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "B2B account not found: " + shipment.getB2bAccountId()));

        if (account.getOwnerUserId() != null && !account.getOwnerUserId().toString().equals(userId)) {
            throw new AccountAccessException(
                    "User " + userId + " does not own account " + account.getId());
        }

        long reversed = Math.max(0L, account.getOutstandingBalancePaise() - shipment.getTotalPricePaise());
        account.setOutstandingBalancePaise(reversed);
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
