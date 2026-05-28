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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ShipmentStateMachine")
class ShipmentStateMachineTest {

    @Mock
    private ShipmentRepository shipmentRepo;
    @Mock
    private ShipmentStateHistoryRepository historyRepo;

    private TransitionRegistry registry;
    private ShipmentStateMachine stateMachine;

    private final UUID shipmentId = UUID.randomUUID();
    private final TransitionContext apiCtx = TransitionContext.fromApi("user-1", "req-1");

    @BeforeEach
    void setUp() {
        // Real registry (no configurers) — tests the full V1 transition table
        registry = new TransitionRegistry(Collections.emptyList());
        registry.initialise();
        stateMachine = new ShipmentStateMachineImpl(shipmentRepo, historyRepo, registry);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private Shipment shipmentIn(ShipmentState state) {
        return shipmentIn(state, PickupType.DA_PICKUP, DropType.DA_DELIVERY, DeliveryType.INTERCITY);
    }

    private Shipment shipmentIn(ShipmentState state, PickupType pickupType,
                                 DropType dropType, DeliveryType deliveryType) {
        Shipment s = new Shipment();
        s.setState(state);
        s.setPickupType(pickupType);
        s.setDropType(dropType);
        s.setDeliveryType(deliveryType);
        when(shipmentRepo.findByIdWithLock(shipmentId)).thenReturn(Optional.of(s));
        return s;
    }

    private void assertHistoryWritten(ShipmentState from, ShipmentState to) {
        ArgumentCaptor<ShipmentStateHistory> cap = ArgumentCaptor.forClass(ShipmentStateHistory.class);
        verify(historyRepo).save(cap.capture());
        assertThat(cap.getValue().getFromState()).isEqualTo(from);
        assertThat(cap.getValue().getToState()).isEqualTo(to);
    }

    // ── Entity not found ──────────────────────────────────────────────────────

    @Test
    void transition_throwsEntityNotFound_whenShipmentMissing() {
        when(shipmentRepo.findByIdWithLock(shipmentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> stateMachine.transition(shipmentId, ShipmentState.PICKUP_ASSIGNED, apiCtx))
                .isInstanceOf(EntityNotFoundException.class);

        verify(historyRepo, never()).save(any());
    }

    // ── DA_PICKUP path (happy paths) ──────────────────────────────────────────

    @Nested
    @DisplayName("DA_PICKUP path — normal flow")
    class DaPickupPath {

        @Test
        void booked_to_pickupAssigned() {
            shipmentIn(ShipmentState.BOOKED, PickupType.DA_PICKUP, DropType.DA_DELIVERY, DeliveryType.INTERCITY);
            stateMachine.transition(shipmentId, ShipmentState.PICKUP_ASSIGNED, apiCtx);
            assertHistoryWritten(ShipmentState.BOOKED, ShipmentState.PICKUP_ASSIGNED);
        }

        @Test
        void pickupAssigned_to_pickedUp() {
            shipmentIn(ShipmentState.PICKUP_ASSIGNED);
            stateMachine.transition(shipmentId, ShipmentState.PICKED_UP, apiCtx);
            assertHistoryWritten(ShipmentState.PICKUP_ASSIGNED, ShipmentState.PICKED_UP);
        }

        @Test
        void pickedUp_to_handedToPickupVan() {
            shipmentIn(ShipmentState.PICKED_UP);
            stateMachine.transition(shipmentId, ShipmentState.HANDED_TO_PICKUP_VAN, apiCtx);
            assertHistoryWritten(ShipmentState.PICKED_UP, ShipmentState.HANDED_TO_PICKUP_VAN);
        }

        @Test
        void handedToPickupVan_to_atOriginHub() {
            shipmentIn(ShipmentState.HANDED_TO_PICKUP_VAN);
            stateMachine.transition(shipmentId, ShipmentState.AT_ORIGIN_HUB, apiCtx);
            assertHistoryWritten(ShipmentState.HANDED_TO_PICKUP_VAN, ShipmentState.AT_ORIGIN_HUB);
        }
    }

    // ── SELF_DROP path ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("SELF_DROP path")
    class SelfDropPath {

        @Test
        void booked_to_awaitingSelfDrop() {
            shipmentIn(ShipmentState.BOOKED, PickupType.SELF_DROP, DropType.DA_DELIVERY, DeliveryType.INTERCITY);
            stateMachine.transition(shipmentId, ShipmentState.AWAITING_SELF_DROP, apiCtx);
            assertHistoryWritten(ShipmentState.BOOKED, ShipmentState.AWAITING_SELF_DROP);
        }

        @Test
        void booked_selfDrop_cannotTransitionToPickupAssigned() {
            shipmentIn(ShipmentState.BOOKED, PickupType.SELF_DROP, DropType.DA_DELIVERY, DeliveryType.INTERCITY);
            assertThatThrownBy(() -> stateMachine.transition(shipmentId, ShipmentState.PICKUP_ASSIGNED, apiCtx))
                    .isInstanceOf(IllegalStateTransitionException.class);
        }

        @Test
        void booked_daPickup_cannotTransitionToAwaitingSelfDrop() {
            shipmentIn(ShipmentState.BOOKED, PickupType.DA_PICKUP, DropType.DA_DELIVERY, DeliveryType.INTERCITY);
            assertThatThrownBy(() -> stateMachine.transition(shipmentId, ShipmentState.AWAITING_SELF_DROP, apiCtx))
                    .isInstanceOf(IllegalStateTransitionException.class);
        }

        @Test
        void awaitingSelfDrop_to_atOriginHub() {
            shipmentIn(ShipmentState.AWAITING_SELF_DROP, PickupType.SELF_DROP, DropType.DA_DELIVERY, DeliveryType.INTERCITY);
            stateMachine.transition(shipmentId, ShipmentState.AT_ORIGIN_HUB, apiCtx);
            assertHistoryWritten(ShipmentState.AWAITING_SELF_DROP, ShipmentState.AT_ORIGIN_HUB);
        }
    }

    // ── Hub processing ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Hub processing")
    class HubProcessing {

        @Test
        void atOriginHub_to_originHubProcessing() {
            shipmentIn(ShipmentState.AT_ORIGIN_HUB);
            stateMachine.transition(shipmentId, ShipmentState.ORIGIN_HUB_PROCESSING, apiCtx);
            assertHistoryWritten(ShipmentState.AT_ORIGIN_HUB, ShipmentState.ORIGIN_HUB_PROCESSING);
        }

        @Test
        void originHubProcessing_to_inTakeoffBag() {
            shipmentIn(ShipmentState.ORIGIN_HUB_PROCESSING);
            stateMachine.transition(shipmentId, ShipmentState.IN_TAKEOFF_BAG, apiCtx);
            assertHistoryWritten(ShipmentState.ORIGIN_HUB_PROCESSING, ShipmentState.IN_TAKEOFF_BAG);
        }
    }

    // ── INTERCITY air leg branching ───────────────────────────────────────────

    @Nested
    @DisplayName("INTERCITY — air leg")
    class IntercityAirLeg {

        @Test
        void inTakeoffBag_intercity_to_dispatchedToAirport() {
            shipmentIn(ShipmentState.IN_TAKEOFF_BAG, PickupType.DA_PICKUP, DropType.DA_DELIVERY, DeliveryType.INTERCITY);
            stateMachine.transition(shipmentId, ShipmentState.DISPATCHED_TO_AIRPORT, apiCtx);
            assertHistoryWritten(ShipmentState.IN_TAKEOFF_BAG, ShipmentState.DISPATCHED_TO_AIRPORT);
        }

        @Test
        void inTakeoffBag_intercity_cannotSkipToHandedToDropVan() {
            shipmentIn(ShipmentState.IN_TAKEOFF_BAG, PickupType.DA_PICKUP, DropType.DA_DELIVERY, DeliveryType.INTERCITY);
            assertThatThrownBy(() -> stateMachine.transition(shipmentId, ShipmentState.HANDED_TO_DROP_VAN, apiCtx))
                    .isInstanceOf(IllegalStateTransitionException.class);
        }

        @Test
        void dispatchedToAirport_to_atAirport() {
            shipmentIn(ShipmentState.DISPATCHED_TO_AIRPORT);
            stateMachine.transition(shipmentId, ShipmentState.AT_AIRPORT, apiCtx);
            assertHistoryWritten(ShipmentState.DISPATCHED_TO_AIRPORT, ShipmentState.AT_AIRPORT);
        }

        @Test
        void atAirport_to_departed() {
            shipmentIn(ShipmentState.AT_AIRPORT);
            stateMachine.transition(shipmentId, ShipmentState.DEPARTED, apiCtx);
            assertHistoryWritten(ShipmentState.AT_AIRPORT, ShipmentState.DEPARTED);
        }

        @Test
        void departed_to_landed() {
            shipmentIn(ShipmentState.DEPARTED);
            stateMachine.transition(shipmentId, ShipmentState.LANDED, apiCtx);
            assertHistoryWritten(ShipmentState.DEPARTED, ShipmentState.LANDED);
        }

        @Test
        void landed_to_dispatchedToHub() {
            shipmentIn(ShipmentState.LANDED);
            stateMachine.transition(shipmentId, ShipmentState.DISPATCHED_TO_HUB, apiCtx);
            assertHistoryWritten(ShipmentState.LANDED, ShipmentState.DISPATCHED_TO_HUB);
        }

        @Test
        void dispatchedToHub_to_atDestHub() {
            shipmentIn(ShipmentState.DISPATCHED_TO_HUB);
            stateMachine.transition(shipmentId, ShipmentState.AT_DEST_HUB, apiCtx);
            assertHistoryWritten(ShipmentState.DISPATCHED_TO_HUB, ShipmentState.AT_DEST_HUB);
        }

        @Test
        void atDestHub_to_destHubProcessing() {
            shipmentIn(ShipmentState.AT_DEST_HUB);
            stateMachine.transition(shipmentId, ShipmentState.DEST_HUB_PROCESSING, apiCtx);
            assertHistoryWritten(ShipmentState.AT_DEST_HUB, ShipmentState.DEST_HUB_PROCESSING);
        }
    }

    // ── SAME_CITY branching ───────────────────────────────────────────────────

    @Nested
    @DisplayName("SAME_CITY — skips air leg")
    class SameCityBranching {

        @Test
        void inTakeoffBag_sameCity_to_handedToDropVan() {
            shipmentIn(ShipmentState.IN_TAKEOFF_BAG, PickupType.DA_PICKUP, DropType.DA_DELIVERY, DeliveryType.SAME_CITY);
            stateMachine.transition(shipmentId, ShipmentState.HANDED_TO_DROP_VAN, apiCtx);
            assertHistoryWritten(ShipmentState.IN_TAKEOFF_BAG, ShipmentState.HANDED_TO_DROP_VAN);
        }

        @Test
        void inTakeoffBag_sameCity_cannotGoToDispatchedToAirport() {
            shipmentIn(ShipmentState.IN_TAKEOFF_BAG, PickupType.DA_PICKUP, DropType.DA_DELIVERY, DeliveryType.SAME_CITY);
            assertThatThrownBy(() -> stateMachine.transition(shipmentId, ShipmentState.DISPATCHED_TO_AIRPORT, apiCtx))
                    .isInstanceOf(IllegalStateTransitionException.class);
        }
    }

    // ── DA_DELIVERY last-mile ─────────────────────────────────────────────────

    @Nested
    @DisplayName("DA_DELIVERY last-mile path")
    class DaDeliveryPath {

        @Test
        void destHubProcessing_daDelivery_to_handedToDropVan() {
            shipmentIn(ShipmentState.DEST_HUB_PROCESSING, PickupType.DA_PICKUP, DropType.DA_DELIVERY, DeliveryType.INTERCITY);
            stateMachine.transition(shipmentId, ShipmentState.HANDED_TO_DROP_VAN, apiCtx);
            assertHistoryWritten(ShipmentState.DEST_HUB_PROCESSING, ShipmentState.HANDED_TO_DROP_VAN);
        }

        @Test
        void destHubProcessing_daDelivery_cannotGoToAwaitingHubCollect() {
            shipmentIn(ShipmentState.DEST_HUB_PROCESSING, PickupType.DA_PICKUP, DropType.DA_DELIVERY, DeliveryType.INTERCITY);
            assertThatThrownBy(() -> stateMachine.transition(shipmentId, ShipmentState.AWAITING_HUB_COLLECT, apiCtx))
                    .isInstanceOf(IllegalStateTransitionException.class);
        }

        @Test
        void handedToDropVan_to_dropAssigned() {
            shipmentIn(ShipmentState.HANDED_TO_DROP_VAN);
            stateMachine.transition(shipmentId, ShipmentState.DROP_ASSIGNED, apiCtx);
            assertHistoryWritten(ShipmentState.HANDED_TO_DROP_VAN, ShipmentState.DROP_ASSIGNED);
        }

        @Test
        void dropAssigned_to_dropCollected() {
            shipmentIn(ShipmentState.DROP_ASSIGNED);
            stateMachine.transition(shipmentId, ShipmentState.DROP_COLLECTED, apiCtx);
            assertHistoryWritten(ShipmentState.DROP_ASSIGNED, ShipmentState.DROP_COLLECTED);
        }

        @Test
        void dropCollected_to_dropped() {
            shipmentIn(ShipmentState.DROP_COLLECTED);
            stateMachine.transition(shipmentId, ShipmentState.DROPPED, apiCtx);
            assertHistoryWritten(ShipmentState.DROP_COLLECTED, ShipmentState.DROPPED);
        }
    }

    // ── HUB_COLLECT path ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("HUB_COLLECT path")
    class HubCollectPath {

        @Test
        void destHubProcessing_hubCollect_to_awaitingHubCollect() {
            shipmentIn(ShipmentState.DEST_HUB_PROCESSING, PickupType.DA_PICKUP, DropType.HUB_COLLECT, DeliveryType.INTERCITY);
            stateMachine.transition(shipmentId, ShipmentState.AWAITING_HUB_COLLECT, apiCtx);
            assertHistoryWritten(ShipmentState.DEST_HUB_PROCESSING, ShipmentState.AWAITING_HUB_COLLECT);
        }

        @Test
        void destHubProcessing_hubCollect_cannotGoToHandedToDropVan() {
            shipmentIn(ShipmentState.DEST_HUB_PROCESSING, PickupType.DA_PICKUP, DropType.HUB_COLLECT, DeliveryType.INTERCITY);
            assertThatThrownBy(() -> stateMachine.transition(shipmentId, ShipmentState.HANDED_TO_DROP_VAN, apiCtx))
                    .isInstanceOf(IllegalStateTransitionException.class);
        }

        @Test
        void awaitingHubCollect_to_hubCollected() {
            shipmentIn(ShipmentState.AWAITING_HUB_COLLECT);
            stateMachine.transition(shipmentId, ShipmentState.HUB_COLLECTED, apiCtx);
            assertHistoryWritten(ShipmentState.AWAITING_HUB_COLLECT, ShipmentState.HUB_COLLECTED);
        }
    }

    // ── Cancellation ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Cancellation paths")
    class CancellationPaths {

        @Test
        void booked_to_cancelled() {
            shipmentIn(ShipmentState.BOOKED);
            stateMachine.transition(shipmentId, ShipmentState.CANCELLED, apiCtx);
            assertHistoryWritten(ShipmentState.BOOKED, ShipmentState.CANCELLED);
        }

        @Test
        void pickupAssigned_to_cancelled() {
            shipmentIn(ShipmentState.PICKUP_ASSIGNED);
            stateMachine.transition(shipmentId, ShipmentState.CANCELLED, apiCtx);
            assertHistoryWritten(ShipmentState.PICKUP_ASSIGNED, ShipmentState.CANCELLED);
        }

        @Test
        void pickedUp_to_cancelled() {
            shipmentIn(ShipmentState.PICKED_UP);
            stateMachine.transition(shipmentId, ShipmentState.CANCELLED, apiCtx);
            assertHistoryWritten(ShipmentState.PICKED_UP, ShipmentState.CANCELLED);
        }

        @Test
        void awaitingSelfDrop_to_cancelled() {
            shipmentIn(ShipmentState.AWAITING_SELF_DROP);
            stateMachine.transition(shipmentId, ShipmentState.CANCELLED, apiCtx);
            assertHistoryWritten(ShipmentState.AWAITING_SELF_DROP, ShipmentState.CANCELLED);
        }

        @Test
        void handedToPickupVan_cannotBeCancelled() {
            shipmentIn(ShipmentState.HANDED_TO_PICKUP_VAN);
            assertThatThrownBy(() -> stateMachine.transition(shipmentId, ShipmentState.CANCELLED, apiCtx))
                    .isInstanceOf(IllegalStateTransitionException.class);
        }
    }

    // ── Exception / failure paths ─────────────────────────────────────────────

    @Nested
    @DisplayName("Failure and RTO paths")
    class FailureAndRtoPaths {

        @Test
        void pickupAssigned_to_pickupFailed() {
            shipmentIn(ShipmentState.PICKUP_ASSIGNED);
            stateMachine.transition(shipmentId, ShipmentState.PICKUP_FAILED, apiCtx);
            assertHistoryWritten(ShipmentState.PICKUP_ASSIGNED, ShipmentState.PICKUP_FAILED);
        }

        @Test
        void pickupFailed_to_pickupAssigned_reschedule() {
            shipmentIn(ShipmentState.PICKUP_FAILED);
            stateMachine.transition(shipmentId, ShipmentState.PICKUP_ASSIGNED, apiCtx);
            assertHistoryWritten(ShipmentState.PICKUP_FAILED, ShipmentState.PICKUP_ASSIGNED);
        }

        @Test
        void dropCollected_to_deliveryFailed() {
            shipmentIn(ShipmentState.DROP_COLLECTED);
            stateMachine.transition(shipmentId, ShipmentState.DELIVERY_FAILED, apiCtx);
            assertHistoryWritten(ShipmentState.DROP_COLLECTED, ShipmentState.DELIVERY_FAILED);
        }

        @Test
        void deliveryFailed_to_rtoInitiated() {
            shipmentIn(ShipmentState.DELIVERY_FAILED);
            stateMachine.transition(shipmentId, ShipmentState.RTO_INITIATED, apiCtx);
            assertHistoryWritten(ShipmentState.DELIVERY_FAILED, ShipmentState.RTO_INITIATED);
        }

        @Test
        void deliveryFailed_to_dropAssigned_reschedule() {
            shipmentIn(ShipmentState.DELIVERY_FAILED);
            stateMachine.transition(shipmentId, ShipmentState.DROP_ASSIGNED, apiCtx);
            assertHistoryWritten(ShipmentState.DELIVERY_FAILED, ShipmentState.DROP_ASSIGNED);
        }

        @Test
        void rtoInitiated_intercity_to_rtoInTransit() {
            shipmentIn(ShipmentState.RTO_INITIATED, PickupType.DA_PICKUP, DropType.DA_DELIVERY, DeliveryType.INTERCITY);
            stateMachine.transition(shipmentId, ShipmentState.RTO_IN_TRANSIT, apiCtx);
            assertHistoryWritten(ShipmentState.RTO_INITIATED, ShipmentState.RTO_IN_TRANSIT);
        }

        @Test
        void rtoInitiated_intercity_cannotJumpToRtoCompleted() {
            shipmentIn(ShipmentState.RTO_INITIATED, PickupType.DA_PICKUP, DropType.DA_DELIVERY, DeliveryType.INTERCITY);
            assertThatThrownBy(() -> stateMachine.transition(shipmentId, ShipmentState.RTO_COMPLETED, apiCtx))
                    .isInstanceOf(IllegalStateTransitionException.class);
        }

        @Test
        void rtoInitiated_sameCity_to_rtoCompleted() {
            shipmentIn(ShipmentState.RTO_INITIATED, PickupType.DA_PICKUP, DropType.DA_DELIVERY, DeliveryType.SAME_CITY);
            stateMachine.transition(shipmentId, ShipmentState.RTO_COMPLETED, apiCtx);
            assertHistoryWritten(ShipmentState.RTO_INITIATED, ShipmentState.RTO_COMPLETED);
        }

        @Test
        void rtoInitiated_sameCity_cannotGoToRtoInTransit() {
            shipmentIn(ShipmentState.RTO_INITIATED, PickupType.DA_PICKUP, DropType.DA_DELIVERY, DeliveryType.SAME_CITY);
            assertThatThrownBy(() -> stateMachine.transition(shipmentId, ShipmentState.RTO_IN_TRANSIT, apiCtx))
                    .isInstanceOf(IllegalStateTransitionException.class);
        }

        @Test
        void rtoInTransit_to_rtoCompleted() {
            shipmentIn(ShipmentState.RTO_IN_TRANSIT);
            stateMachine.transition(shipmentId, ShipmentState.RTO_COMPLETED, apiCtx);
            assertHistoryWritten(ShipmentState.RTO_IN_TRANSIT, ShipmentState.RTO_COMPLETED);
        }
    }

    // ── Terminal states ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Terminal states reject all transitions")
    class TerminalStates {

        @Test
        void dropped_isTerminal() {
            shipmentIn(ShipmentState.DROPPED);
            assertThatThrownBy(() -> stateMachine.transition(shipmentId, ShipmentState.DELIVERY_FAILED, apiCtx))
                    .isInstanceOf(IllegalStateTransitionException.class);
        }

        @Test
        void hubCollected_isTerminal() {
            shipmentIn(ShipmentState.HUB_COLLECTED);
            assertThatThrownBy(() -> stateMachine.transition(shipmentId, ShipmentState.DELIVERY_FAILED, apiCtx))
                    .isInstanceOf(IllegalStateTransitionException.class);
        }

        @Test
        void rtoCompleted_isTerminal() {
            shipmentIn(ShipmentState.RTO_COMPLETED);
            assertThatThrownBy(() -> stateMachine.transition(shipmentId, ShipmentState.BOOKED, apiCtx))
                    .isInstanceOf(IllegalStateTransitionException.class);
        }

        @Test
        void cancelled_isTerminal() {
            shipmentIn(ShipmentState.CANCELLED);
            assertThatThrownBy(() -> stateMachine.transition(shipmentId, ShipmentState.BOOKED, apiCtx))
                    .isInstanceOf(IllegalStateTransitionException.class);
        }
    }

    // ── IllegalStateTransitionException carries correct metadata ─────────────

    @Test
    void illegalTransitionException_carriesFromAndToState() {
        shipmentIn(ShipmentState.DROPPED);

        assertThatThrownBy(() -> stateMachine.transition(shipmentId, ShipmentState.BOOKED, apiCtx))
                .isInstanceOf(IllegalStateTransitionException.class)
                .satisfies(ex -> {
                    IllegalStateTransitionException iste = (IllegalStateTransitionException) ex;
                    assertThat(iste.getFromState()).isEqualTo(ShipmentState.DROPPED);
                    assertThat(iste.getToState()).isEqualTo(ShipmentState.BOOKED);
                    assertThat(iste.getMessage()).contains("DROPPED").contains("BOOKED");
                });
    }

    // ── TransitionContext is written to history ───────────────────────────────

    @Test
    void transition_writesFullContextToHistory() {
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
        assertThat(history.getOccurredAt()).isNotNull();
    }

    // ── Dynamic registration (extension point) ────────────────────────────────

    @Test
    void dynamicRegistration_newTransitionIsAccepted() {
        // Simulates a V2 module adding a transition via TransitionRegistryConfigurer
        TransitionRegistryConfigurer extender = reg ->
                reg.register(ShipmentState.DROPPED, ShipmentState.RTO_INITIATED);

        TransitionRegistry extendedRegistry = new TransitionRegistry(List.of(extender));
        extendedRegistry.initialise();
        ShipmentStateMachine extended = new ShipmentStateMachineImpl(shipmentRepo, historyRepo, extendedRegistry);

        shipmentIn(ShipmentState.DROPPED);
        // Should NOT throw — transition was registered dynamically
        extended.transition(shipmentId, ShipmentState.RTO_INITIATED, apiCtx);
        assertHistoryWritten(ShipmentState.DROPPED, ShipmentState.RTO_INITIATED);
    }
}
