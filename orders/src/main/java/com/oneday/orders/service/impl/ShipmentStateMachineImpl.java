package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.DropType;
import com.oneday.common.domain.enums.PickupType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.domain.ShipmentStateHistory;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.repository.ShipmentStateHistoryRepository;
import com.oneday.orders.service.ShipmentStateMachine;
import com.oneday.orders.service.TransitionContext;
import com.oneday.orders.service.TransitionRegistry;
import com.oneday.orders.events.ShipmentTransitioned;
import com.oneday.orders.service.exception.IllegalStateTransitionException;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
class ShipmentStateMachineImpl implements ShipmentStateMachine {

    private final ShipmentRepository shipmentRepo;
    private final ShipmentStateHistoryRepository historyRepo;
    private final TransitionRegistry registry;
    private final ApplicationEventPublisher applicationEventPublisher;

    ShipmentStateMachineImpl(ShipmentRepository shipmentRepo,
                              ShipmentStateHistoryRepository historyRepo,
                              TransitionRegistry registry,
                              ApplicationEventPublisher applicationEventPublisher) {
        this.shipmentRepo = shipmentRepo;
        this.historyRepo = historyRepo;
        this.registry = registry;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public void transition(UUID shipmentId, ShipmentState target, TransitionContext ctx) {
        // SELECT FOR UPDATE — prevents concurrent transitions on the same shipment
        Shipment shipment = shipmentRepo.findByIdWithLock(shipmentId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Shipment not found: " + shipmentId));

        ShipmentState current = shipment.getState();
        Set<ShipmentState> allowed = computeAllowed(shipment, current);

        if (!allowed.contains(target)) {
            throw new IllegalStateTransitionException(current, target);
        }

        shipment.setState(target);
        // No explicit save() — shipment is a managed entity; Hibernate dirty-checks
        // and flushes the UPDATE automatically when the transaction commits.

        historyRepo.save(ShipmentStateHistory.of(shipmentId, current, target, ctx));

        // In-process announcement — ShipmentEventProducer turns this into the outbound Kafka
        // STATE_CHANGED event after this transaction commits (AFTER_COMMIT). The state machine
        // stays decoupled from Kafka; it just reports that a transition happened.
        applicationEventPublisher.publishEvent(new ShipmentTransitioned(
                shipmentId, shipment.getShipmentRef(), current, target,
                ctx.getTriggeredBy(), ctx.getTriggerSource(), ctx.getOccurredAt()));
    }

    /**
     * Returns the set of states actually reachable from {@code current} for this
     * specific shipment, applying pickup_type / drop_type / delivery_type branching
     * on top of the registry's base set.
     */
    private Set<ShipmentState> computeAllowed(Shipment shipment, ShipmentState current) {
        Set<ShipmentState> base = EnumSet.copyOf(
                registry.getAllowedTargets(current).isEmpty()
                        ? EnumSet.noneOf(ShipmentState.class)
                        : registry.getAllowedTargets(current));

        switch (current) {
            case BOOKED -> {
                // pickup_type determines which initial path is taken
                if (shipment.getPickupType() == PickupType.DA_PICKUP) {
                    base.remove(ShipmentState.AWAITING_SELF_DROP);
                } else if (shipment.getPickupType() == PickupType.SELF_DROP) {
                    base.remove(ShipmentState.PICKUP_ASSIGNED);
                }
            }
            case IN_TAKEOFF_BAG -> {
                // delivery_type determines whether the air leg is taken
                if (shipment.getDeliveryType() == DeliveryType.INTERCITY) {
                    base.remove(ShipmentState.HANDED_TO_DROP_VAN);
                } else if (shipment.getDeliveryType() == DeliveryType.SAME_CITY) {
                    base.remove(ShipmentState.DISPATCHED_TO_AIRPORT);
                }
            }
            case DEST_HUB_PROCESSING -> {
                // drop_type determines whether a DA delivers or customer collects from hub
                if (shipment.getDropType() == DropType.DA_DELIVERY) {
                    base.remove(ShipmentState.AWAITING_HUB_COLLECT);
                } else if (shipment.getDropType() == DropType.HUB_COLLECT) {
                    base.remove(ShipmentState.HANDED_TO_DROP_VAN);
                }
            }
            case RTO_INITIATED -> {
                // delivery_type determines whether there is a return air leg
                if (shipment.getDeliveryType() == DeliveryType.INTERCITY) {
                    base.remove(ShipmentState.RTO_COMPLETED);
                } else if (shipment.getDeliveryType() == DeliveryType.SAME_CITY) {
                    base.remove(ShipmentState.RTO_IN_TRANSIT);
                }
            }
            default -> { /* no branching — base set is the full allowed set */ }
        }

        return base;
    }
}
