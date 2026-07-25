package com.oneday.orders.tracking;

import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.DropType;
import com.oneday.common.domain.enums.PickupType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.domain.ShipmentStateHistory;
import com.oneday.orders.repository.ShipmentStateHistoryRepository;
import com.oneday.orders.service.CustomerVisibleStateMapper;
import com.oneday.orders.tracking.MilestoneBuilder.Milestone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("MilestoneBuilder")
class MilestoneBuilderTest {

    private final ShipmentStateHistoryRepository historyRepository = mock(ShipmentStateHistoryRepository.class);
    private final MilestoneBuilder builder = new MilestoneBuilder(historyRepository, new CustomerVisibleStateMapper());

    @Test
    void freshBookingShowsDoneOrderConfirmedThenPendingSteps() {
        history(hist(ShipmentState.BOOKED));
        List<Milestone> out = builder.build(intercity(ShipmentState.BOOKED));

        assertThat(out.get(0).label()).isEqualTo("Order confirmed");
        assertThat(out.get(0).done()).isTrue();
        assertThat(out.get(0).occurredAt()).isNotNull();
        // Everything after is a pending preview of the journey.
        assertThat(out.subList(1, out.size())).allMatch(m -> !m.done() && m.occurredAt() == null);
        assertThat(labels(out)).contains("Parcel collected", "In transit by air", "Out for delivery", "Delivered");
    }

    @Test
    void deliveredShipmentHasNoPendingSteps() {
        history(
                hist(ShipmentState.BOOKED), hist(ShipmentState.PICKED_UP), hist(ShipmentState.AT_ORIGIN_HUB),
                hist(ShipmentState.DEPARTED), hist(ShipmentState.AT_DEST_HUB),
                hist(ShipmentState.HANDED_TO_DROP_VAN), hist(ShipmentState.DROPPED));
        List<Milestone> out = builder.build(intercity(ShipmentState.DROPPED));

        assertThat(out).allMatch(Milestone::done);
        assertThat(out.get(out.size() - 1).label()).isEqualTo("Delivered");
    }

    @Test
    void sameCityHasNoAirStepsInPreview() {
        history(hist(ShipmentState.BOOKED));
        List<Milestone> out = builder.build(
                shipment(ShipmentState.BOOKED, DeliveryType.SAME_CITY, PickupType.DA_PICKUP, DropType.DA_DELIVERY));
        assertThat(labels(out)).doesNotContain("In transit by air", "Arrived at destination hub");
        assertThat(labels(out)).contains("Out for delivery", "Delivered");
    }

    @Test
    void hubCollectPreviewsCollectionNotDelivery() {
        history(hist(ShipmentState.BOOKED));
        List<Milestone> out = builder.build(
                shipment(ShipmentState.BOOKED, DeliveryType.INTERCITY, PickupType.DA_PICKUP, DropType.HUB_COLLECT));
        assertThat(labels(out)).contains("Your parcel is ready — collect from the hub", "Collected from hub");
        assertThat(labels(out)).doesNotContain("Out for delivery");
    }

    // ── fixtures ──────────────────────────────────────────────────────────
    private void history(ShipmentStateHistory... rows) {
        when(historyRepository.findByShipmentIdOrderByOccurredAtAsc(any())).thenReturn(List.of(rows));
    }

    private static List<String> labels(List<Milestone> out) {
        return out.stream().map(Milestone::label).toList();
    }

    private static ShipmentStateHistory hist(ShipmentState to) {
        return ShipmentStateHistory.builder().toState(to).occurredAt(Instant.now()).build();
    }

    private static Shipment intercity(ShipmentState state) {
        return shipment(state, DeliveryType.INTERCITY, PickupType.DA_PICKUP, DropType.DA_DELIVERY);
    }

    private static Shipment shipment(ShipmentState state, DeliveryType dt, PickupType pt, DropType drt) {
        Shipment s = new Shipment();
        s.setState(state);
        s.setDeliveryType(dt);
        s.setPickupType(pt);
        s.setDropType(drt);
        return s;
    }
}
