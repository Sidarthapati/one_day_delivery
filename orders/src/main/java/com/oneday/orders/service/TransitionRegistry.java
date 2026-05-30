package com.oneday.orders.service;

import com.oneday.common.domain.enums.ShipmentState;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Holds the allowed state transitions for the M4 shipment state machine.
 *
 * <p>The V1 base transitions are registered in {@link #initialise()}. Any Spring bean
 * implementing {@link TransitionRegistryConfigurer} is applied afterwards, allowing
 * future modules or V2 flows to add transitions without modifying this class.</p>
 *
 * <p>Runtime branching (pickup_type, drop_type, delivery_type) is handled by
 * {@link impl.ShipmentStateMachineImpl}, which narrows the set returned here based on
 * the shipment's own fields. The registry intentionally contains both possible targets
 * for branching states (e.g. {@code IN_TAKEOFF_BAG} allows both
 * {@code DISPATCHED_TO_AIRPORT} and {@code HANDED_TO_DROP_VAN}); the impl filters
 * the set down to the one that matches the shipment's delivery_type at transition time.</p>
 */
@Component
public class TransitionRegistry {

    private final Map<ShipmentState, Set<ShipmentState>> transitions =
            new EnumMap<>(ShipmentState.class);

    private final List<TransitionRegistryConfigurer> configurers;

    public TransitionRegistry(List<TransitionRegistryConfigurer> configurers) {
        this.configurers = configurers;
    }

    @PostConstruct
    public void initialise() {
        // ── Normal pickup path (DA_PICKUP) ────────────────────────────────────
        register(ShipmentState.BOOKED,                ShipmentState.PICKUP_ASSIGNED);
        register(ShipmentState.BOOKED,                ShipmentState.AWAITING_SELF_DROP);
        register(ShipmentState.BOOKED,                ShipmentState.CANCELLED);

        register(ShipmentState.AWAITING_SELF_DROP,    ShipmentState.AT_ORIGIN_HUB);
        register(ShipmentState.AWAITING_SELF_DROP,    ShipmentState.CANCELLED);

        register(ShipmentState.PICKUP_ASSIGNED,       ShipmentState.PICKED_UP);
        register(ShipmentState.PICKUP_ASSIGNED,       ShipmentState.PICKUP_FAILED);
        register(ShipmentState.PICKUP_ASSIGNED,       ShipmentState.CANCELLED);

        register(ShipmentState.PICKED_UP,             ShipmentState.HANDED_TO_PICKUP_VAN);
        register(ShipmentState.PICKED_UP,             ShipmentState.CANCELLED);

        register(ShipmentState.HANDED_TO_PICKUP_VAN,  ShipmentState.AT_ORIGIN_HUB);

        // ── Hub processing ────────────────────────────────────────────────────
        register(ShipmentState.AT_ORIGIN_HUB,         ShipmentState.ORIGIN_HUB_PROCESSING);
        register(ShipmentState.ORIGIN_HUB_PROCESSING, ShipmentState.IN_TAKEOFF_BAG);

        // ── Intercity air leg (delivery_type branching at IN_TAKEOFF_BAG) ─────
        // Both targets are registered; ShipmentStateMachineImpl filters to one at runtime.
        register(ShipmentState.IN_TAKEOFF_BAG,        ShipmentState.DISPATCHED_TO_AIRPORT); // INTERCITY
        register(ShipmentState.IN_TAKEOFF_BAG,        ShipmentState.HANDED_TO_DROP_VAN);    // SAME_CITY

        register(ShipmentState.DISPATCHED_TO_AIRPORT, ShipmentState.AT_AIRPORT);
        register(ShipmentState.AT_AIRPORT,            ShipmentState.DEPARTED);
        register(ShipmentState.DEPARTED,              ShipmentState.LANDED);
        register(ShipmentState.LANDED,                ShipmentState.DISPATCHED_TO_HUB);
        register(ShipmentState.DISPATCHED_TO_HUB,     ShipmentState.AT_DEST_HUB);
        register(ShipmentState.AT_DEST_HUB,           ShipmentState.DEST_HUB_PROCESSING);

        // ── Last-mile delivery (drop_type branching at DEST_HUB_PROCESSING) ───
        // Both targets are registered; impl filters to one at runtime.
        register(ShipmentState.DEST_HUB_PROCESSING,   ShipmentState.HANDED_TO_DROP_VAN);    // DA_DELIVERY
        register(ShipmentState.DEST_HUB_PROCESSING,   ShipmentState.AWAITING_HUB_COLLECT);  // HUB_COLLECT

        register(ShipmentState.AWAITING_HUB_COLLECT,  ShipmentState.HUB_COLLECTED);

        register(ShipmentState.HANDED_TO_DROP_VAN,    ShipmentState.DROP_ASSIGNED);
        register(ShipmentState.DROP_ASSIGNED,         ShipmentState.DROP_COLLECTED);
        register(ShipmentState.DROP_COLLECTED,        ShipmentState.DROPPED);
        register(ShipmentState.DROP_COLLECTED,        ShipmentState.DELIVERY_FAILED);

        // ── Exception / failure paths (M11-driven) ───────────────────────────
        register(ShipmentState.PICKUP_FAILED,         ShipmentState.PICKUP_ASSIGNED);
        register(ShipmentState.PICKUP_FAILED,         ShipmentState.CANCELLED);

        register(ShipmentState.DELIVERY_FAILED,       ShipmentState.RTO_INITIATED);
        register(ShipmentState.DELIVERY_FAILED,       ShipmentState.DROP_ASSIGNED);

        // ── RTO path (delivery_type branching at RTO_INITIATED) ──────────────
        // Both targets registered; impl filters to one at runtime.
        register(ShipmentState.RTO_INITIATED,         ShipmentState.RTO_IN_TRANSIT);  // INTERCITY
        register(ShipmentState.RTO_INITIATED,         ShipmentState.RTO_COMPLETED);   // SAME_CITY

        register(ShipmentState.RTO_IN_TRANSIT,        ShipmentState.RTO_COMPLETED);

        // Terminal states: DROPPED, HUB_COLLECTED, RTO_COMPLETED, CANCELLED
        // — intentionally not registered; getAllowedTargets returns empty set for them.

        // Apply extension configurers (e.g. V2 flows registered by other modules)
        configurers.forEach(c -> c.configure(this));
    }

    /**
     * Registers a single {@code from → to} transition. Safe to call from
     * {@link TransitionRegistryConfigurer} implementations at startup.
     */
    public void register(ShipmentState from, ShipmentState to) {
        transitions.computeIfAbsent(from, k -> EnumSet.noneOf(ShipmentState.class)).add(to);
    }

    /**
     * Returns the set of states reachable from {@code from} according to the registry.
     * Note: this is the <em>full</em> registry set — the state machine impl narrows it
     * further for branching states (pickup_type / drop_type / delivery_type).
     *
     * @return an unmodifiable view; empty if {@code from} is a terminal state
     */
    public Set<ShipmentState> getAllowedTargets(ShipmentState from) {
        return Collections.unmodifiableSet(
                transitions.getOrDefault(from, EnumSet.noneOf(ShipmentState.class)));
    }
}
