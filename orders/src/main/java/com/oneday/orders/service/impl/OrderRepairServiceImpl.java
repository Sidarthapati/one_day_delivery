package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.log.AuditLog;
import com.oneday.orders.domain.ParcelOrder;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.dto.B2bBookingRequest;
import com.oneday.orders.dto.BookingResponse;
import com.oneday.orders.dto.OrderCancellationSummary;
import com.oneday.orders.dto.OrderCancellationSummary.SkippedShipment;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.repository.ParcelOrderRepository;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.B2bBookingService;
import com.oneday.orders.service.CancellationPolicy;
import com.oneday.orders.service.CancellationService;
import com.oneday.orders.service.OrderCapacityService;
import com.oneday.orders.service.OrderRepairService;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * @see OrderRepairService
 */
@Service
class OrderRepairServiceImpl implements OrderRepairService {

    private static final Logger log = LoggerFactory.getLogger(OrderRepairServiceImpl.class);

    private final ParcelOrderRepository orderRepository;
    private final ShipmentRepository shipmentRepository;
    private final B2bAccountRepository accountRepository;
    private final B2bBookingService b2bBookingService;
    private final CancellationService cancellationService;
    private final CancellationPolicy cancellationPolicy;
    private final OrderCapacityService capacityService;

    OrderRepairServiceImpl(ParcelOrderRepository orderRepository,
                           ShipmentRepository shipmentRepository,
                           B2bAccountRepository accountRepository,
                           B2bBookingService b2bBookingService,
                           CancellationService cancellationService,
                           CancellationPolicy cancellationPolicy,
                           OrderCapacityService capacityService) {
        this.orderRepository = orderRepository;
        this.shipmentRepository = shipmentRepository;
        this.accountRepository = accountRepository;
        this.b2bBookingService = b2bBookingService;
        this.cancellationService = cancellationService;
        this.cancellationPolicy = cancellationPolicy;
        this.capacityService = capacityService;
    }

    @Override
    public BookingResponse addShipment(String orderRef, B2bBookingRequest request,
                                       String idempotencyKey, String userId) {
        ParcelOrder order = resolveOwnedB2bOrder(orderRef, userId);

        List<Shipment> siblings = shipmentRepository.findByOrderIdOrderByCreatedAtAsc(order.getId());
        if (!isEditable(siblings)) {
            throw new OrderNotEditableException(
                    "Order " + orderRef + " can no longer be edited — all parcels are already past pickup.");
        }

        // Capacity gate — only bites when a DA is already on this order's pickup.
        int chargeableGrams = chargeableWeightGrams(request);
        capacityService.ensureCapacityForAdd(order, chargeableGrams);

        // Always book onto THIS order's account (the caller was verified to own it), so a mismatched
        // b2b_account_id in the request body can never divert the charge to another account.
        request.setB2bAccountId(order.getB2bAccountId());

        BookingResponse response = b2bBookingService.book(request, idempotencyKey, userId, order.getId());

        AuditLog.event("order.repair.shipment_added")
                .kv("orderRef", orderRef)
                .kv("shipmentRef", response.getShipmentRef())
                .kv("chargeableGrams", chargeableGrams)
                .log();
        return response;
    }

    @Override
    public OrderCancellationSummary cancelOrder(String orderRef, String reason, String userId) {
        ParcelOrder order = resolveOwnedB2bOrder(orderRef, userId);

        List<String> cancelled = new ArrayList<>();
        List<SkippedShipment> skipped = new ArrayList<>();

        for (Shipment s : shipmentRepository.findByOrderIdOrderByCreatedAtAsc(order.getId())) {
            if (s.getState() == ShipmentState.CANCELLED || s.getCancelledAt() != null) {
                continue;   // already gone — not part of this action's summary
            }
            if (!cancellationPolicy.isCancellable(s.getState(), s.getPickupType())) {
                skipped.add(new SkippedShipment(s.getShipmentRef(), s.getState().name()));
                continue;
            }
            try {
                // Each cancel is its own transaction (refund + rollup decrement + events), so a later
                // failure never rolls back an earlier successful cancel — the partial result stands.
                cancellationService.cancel(s.getShipmentRef(), reason, userId, true);
                cancelled.add(s.getShipmentRef());
            } catch (RuntimeException e) {
                log.warn("Order-cancel: could not cancel {} of order {}: {}",
                        s.getShipmentRef(), orderRef, e.getMessage());
                skipped.add(new SkippedShipment(s.getShipmentRef(), s.getState().name()));
            }
        }

        AuditLog.event("order.repair.order_cancelled")
                .kv("orderRef", orderRef)
                .kv("cancelledCount", cancelled.size())
                .kv("skippedCount", skipped.size())
                .log();
        return new OrderCancellationSummary(orderRef, cancelled, skipped);
    }

    /**
     * Resolve the order and confirm the caller owns its B2B account. 404 (not 403) on any mismatch —
     * missing order, non-B2B order, or another account's order — so a caller can't probe for orders
     * outside its own account (same reasoning as the cancellation lane/ownership guards).
     */
    private ParcelOrder resolveOwnedB2bOrder(String orderRef, String userId) {
        ParcelOrder order = orderRepository.findByOrderRef(orderRef)
                .orElseThrow(() -> new EntityNotFoundException("Order not found: " + orderRef));

        if (order.getCustomerType() != CustomerType.B2B || order.getB2bAccountId() == null) {
            throw new EntityNotFoundException("Order not found: " + orderRef);
        }

        UUID callerAccountId = accountRepository.findByMemberUserId(parseUserId(userId))
                .map(a -> a.getId())
                .orElse(null);
        if (callerAccountId == null || !callerAccountId.equals(order.getB2bAccountId())) {
            throw new EntityNotFoundException("Order not found: " + orderRef);
        }
        return order;
    }

    /** An order is still editable while at least one child is within its cancellation window. */
    private boolean isEditable(List<Shipment> siblings) {
        return siblings.stream()
                .anyMatch(s -> s.getState() != ShipmentState.CANCELLED
                        && cancellationPolicy.isCancellable(s.getState(), s.getPickupType()));
    }

    /** Same chargeable-weight math as booking: max(actual, volumetric = L·W·H / 5). */
    private int chargeableWeightGrams(B2bBookingRequest request) {
        int volumetric = (request.getLengthCm() * request.getWidthCm() * request.getHeightCm()) / 5;
        return Math.max(request.getWeightGrams(), volumetric);
    }

    private UUID parseUserId(String userId) {
        try {
            return UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            throw new EntityNotFoundException("Order not found");   // malformed principal → treat as no access
        }
    }
}
