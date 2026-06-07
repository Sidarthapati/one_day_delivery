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
import com.oneday.orders.service.TransitionRegistryConfigurer;
import com.oneday.orders.service.exception.IllegalStateTransitionException;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused unit coverage of the shipment state machine. The full end-to-end lifecycle is exercised
 * by the e2e suite; this class pins the pure transition-table invariants with representative samples
 * rather than the exhaustive 27×27 matrix: one transition per structural branch (DA-pickup,
 * same-city air-skip, hub-collect), plus the rejection/terminal/extension-point rules.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShipmentStateMachine")
class ShipmentStateMachineTest {

    @Mock private ShipmentRepository shipmentRepo;
    @Mock private ShipmentStateHistoryRepository historyRepo;
    @Mock private ApplicationEventPublisher applicationEventPublisher;

    private ShipmentStateMachine stateMachine;

    private final UUID shipmentId = UUID.randomUUID();
    private final TransitionContext apiCtx = TransitionContext.fromApi("user-1", "req-1");

    @BeforeEach
    void setUp() {
        TransitionRegistry registry = new TransitionRegistry(Collections.emptyList());
        registry.initialise();
        stateMachine = new ShipmentStateMachineImpl(shipmentRepo, historyRepo, registry, applicationEventPublisher);
    }

    // A standard DA-pickup forward step (BOOKED → PICKUP_ASSIGNED) is accepted and recorded in
    // the append-only state history.
    @Test
    void legalForwardTransition_succeedsAndWritesHistory() {
        shipmentIn(ShipmentState.BOOKED, PickupType.DA_PICKUP, DropType.DA_DELIVERY, DeliveryType.INTERCITY);

        stateMachine.transition(shipmentId, ShipmentState.PICKUP_ASSIGNED, apiCtx);

        assertHistoryWritten(ShipmentState.BOOKED, ShipmentState.PICKUP_ASSIGNED);
    }

    // SAME_CITY branch: a same-city parcel skips the air legs — IN_TAKEOFF_BAG goes straight to
    // HANDED_TO_DROP_VAN instead of DISPATCHED_TO_AIRPORT.
    @Test
    void sameCityBranch_skipsAirLegs() {
        shipmentIn(ShipmentState.IN_TAKEOFF_BAG, PickupType.DA_PICKUP, DropType.DA_DELIVERY, DeliveryType.SAME_CITY);

        stateMachine.transition(shipmentId, ShipmentState.HANDED_TO_DROP_VAN, apiCtx);

        assertHistoryWritten(ShipmentState.IN_TAKEOFF_BAG, ShipmentState.HANDED_TO_DROP_VAN);
    }

    // HUB_COLLECT branch: a hub-collect parcel diverts at the destination hub to AWAITING_HUB_COLLECT
    // rather than going out for delivery.
    @Test
    void hubCollectBranch_divertsToAwaitingHubCollect() {
        shipmentIn(ShipmentState.DEST_HUB_PROCESSING, PickupType.DA_PICKUP, DropType.HUB_COLLECT, DeliveryType.INTERCITY);

        stateMachine.transition(shipmentId, ShipmentState.AWAITING_HUB_COLLECT, apiCtx);

        assertHistoryWritten(ShipmentState.DEST_HUB_PROCESSING, ShipmentState.AWAITING_HUB_COLLECT);
    }

    // Cancellation is reachable from an early state (BOOKED → CANCELLED).
    @Test
    void cancellation_fromBooked_succeeds() {
        shipmentIn(ShipmentState.BOOKED);

        stateMachine.transition(shipmentId, ShipmentState.CANCELLED, apiCtx);

        assertHistoryWritten(ShipmentState.BOOKED, ShipmentState.CANCELLED);
    }

    // An illegal jump (BOOKED → DROPPED) is rejected, the exception carries both states, and no
    // history row is written.
    @Test
    void illegalTransition_isRejectedWithMetadata_andWritesNoHistory() {
        shipmentIn(ShipmentState.BOOKED);

        assertThatThrownBy(() -> stateMachine.transition(shipmentId, ShipmentState.DROPPED, apiCtx))
                .isInstanceOf(IllegalStateTransitionException.class)
                .satisfies(ex -> {
                    IllegalStateTransitionException iste = (IllegalStateTransitionException) ex;
                    assertThat(iste.getFromState()).isEqualTo(ShipmentState.BOOKED);
                    assertThat(iste.getToState()).isEqualTo(ShipmentState.DROPPED);
                });

        verify(historyRepo, never()).save(any());
    }

    // A terminal state (DROPPED) has no legal exits — any further transition is rejected.
    @Test
    void terminalState_hasNoExits() {
        shipmentIn(ShipmentState.DROPPED);

        assertThatThrownBy(() -> stateMachine.transition(shipmentId, ShipmentState.DELIVERY_FAILED, apiCtx))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    // A transition against a non-existent shipment fails fast with no history written.
    @Test
    void unknownShipment_throwsEntityNotFound() {
        when(shipmentRepo.findByIdWithLock(shipmentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> stateMachine.transition(shipmentId, ShipmentState.PICKUP_ASSIGNED, apiCtx))
                .isInstanceOf(EntityNotFoundException.class);

        verify(historyRepo, never()).save(any());
    }

    // The full trigger context (actor, event ref, notes) is persisted onto the history row — the
    // append-only audit trail that downstream modules and support rely on.
    @Test
    void transition_writesFullTriggerContextToHistory() {
        shipmentIn(ShipmentState.PICKUP_ASSIGNED);
        TransitionContext kafkaCtx = TransitionContext.fromKafka("m5-consumer", "kafka-msg-001")
                .withNotes("DA arrived and customer confirmed OTP");

        stateMachine.transition(shipmentId, ShipmentState.PICKED_UP, kafkaCtx);

        ArgumentCaptor<ShipmentStateHistory> cap = ArgumentCaptor.forClass(ShipmentStateHistory.class);
        verify(historyRepo).save(cap.capture());
        ShipmentStateHistory history = cap.getValue();
        assertThat(history.getTriggeredBy()).isEqualTo("m5-consumer");
        assertThat(history.getEventRef()).isEqualTo("kafka-msg-001");
        assertThat(history.getNotes()).isEqualTo("DA arrived and customer confirmed OTP");
    }

    // Extension point: a later module can register a new transition at runtime via a configurer,
    // and the machine then accepts it (here DROPPED → RTO_INITIATED).
    @Test
    void dynamicRegistration_acceptsNewTransition() {
        TransitionRegistryConfigurer extender = reg ->
                reg.register(ShipmentState.DROPPED, ShipmentState.RTO_INITIATED);
        TransitionRegistry extendedRegistry = new TransitionRegistry(List.of(extender));
        extendedRegistry.initialise();
        ShipmentStateMachine extended = new ShipmentStateMachineImpl(
                shipmentRepo, historyRepo, extendedRegistry, applicationEventPublisher);
        shipmentIn(ShipmentState.DROPPED);

        assertThatNoException().isThrownBy(() ->
                extended.transition(shipmentId, ShipmentState.RTO_INITIATED, apiCtx));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void shipmentIn(ShipmentState state) {
        shipmentIn(state, PickupType.DA_PICKUP, DropType.DA_DELIVERY, DeliveryType.INTERCITY);
    }

    private void shipmentIn(ShipmentState state, PickupType pickupType,
                            DropType dropType, DeliveryType deliveryType) {
        Shipment s = new Shipment();
        s.setState(state);
        s.setPickupType(pickupType);
        s.setDropType(dropType);
        s.setDeliveryType(deliveryType);
        when(shipmentRepo.findByIdWithLock(shipmentId)).thenReturn(Optional.of(s));
    }

    private void assertHistoryWritten(ShipmentState from, ShipmentState to) {
        ArgumentCaptor<ShipmentStateHistory> cap = ArgumentCaptor.forClass(ShipmentStateHistory.class);
        verify(historyRepo).save(cap.capture());
        assertThat(cap.getValue().getFromState()).isEqualTo(from);
        assertThat(cap.getValue().getToState()).isEqualTo(to);
    }
}
