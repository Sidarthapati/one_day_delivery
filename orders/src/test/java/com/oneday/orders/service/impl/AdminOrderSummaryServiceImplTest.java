package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.dto.ShipmentAgeingStats;
import com.oneday.orders.dto.ShipmentSummaryStats;
import com.oneday.orders.repository.AgeingBandCount;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.repository.StateCount;
import com.oneday.orders.service.OpsBucket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminOrderSummaryServiceImplTest {

    @Mock
    ShipmentRepository shipmentRepository;

    private record Count(ShipmentState state, long count) implements StateCount {
        @Override public ShipmentState getState() { return state; }
        @Override public long getCount() { return count; }
    }

    /** The exhaustive switch guarantees this at compile time; this asserts it at runtime too, so a
     *  future refactor that reintroduces a default can't silently drop a state from every bucket. */
    @Test
    void everyStateMapsToABucket() {
        for (ShipmentState s : ShipmentState.values()) {
            assertThat(OpsBucket.of(s)).as("bucket for %s", s).isNotNull();
        }
    }

    @Test
    void bucketsAndByStateSumToTotal() {
        when(shipmentRepository.countByState()).thenReturn(List.of(
                new Count(ShipmentState.BOOKED, 5),              // BOOKED_PICKUP
                new Count(ShipmentState.PICKED_UP, 3),           // BOOKED_PICKUP
                new Count(ShipmentState.AT_AIRPORT, 7),          // IN_TRANSIT
                new Count(ShipmentState.DROP_ASSIGNED, 2),       // OUT_FOR_DELIVERY
                new Count(ShipmentState.DROPPED, 10),            // DELIVERED
                new Count(ShipmentState.DELIVERY_FAILED, 4)));   // EXCEPTIONS

        AdminOrderSummaryServiceImpl svc = new AdminOrderSummaryServiceImpl(shipmentRepository);
        ShipmentSummaryStats stats = svc.summary(null);

        assertThat(stats.total()).isEqualTo(31);
        // All five buckets present; each equals the sum of its states.
        assertThat(stats.buckets()).containsOnlyKeys(
                "BOOKED_PICKUP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED", "EXCEPTIONS");
        assertThat(stats.buckets().get("BOOKED_PICKUP")).isEqualTo(8);
        assertThat(stats.buckets().get("IN_TRANSIT")).isEqualTo(7);
        assertThat(stats.buckets().get("OUT_FOR_DELIVERY")).isEqualTo(2);
        assertThat(stats.buckets().get("DELIVERED")).isEqualTo(10);
        assertThat(stats.buckets().get("EXCEPTIONS")).isEqualTo(4);
        // Bucket totals and raw-state totals both reconcile to total.
        assertThat(stats.buckets().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(31);
        assertThat(stats.byState().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(31);
    }

    @Test
    void secondCallWithinTtlIsServedFromCache() {
        when(shipmentRepository.countByState()).thenReturn(List.of(new Count(ShipmentState.BOOKED, 1)));
        AdminOrderSummaryServiceImpl svc = new AdminOrderSummaryServiceImpl(shipmentRepository);

        svc.summary(null);
        svc.summary(null);

        verify(shipmentRepository, times(1)).countByState();
    }

    private record Band(String state, int band, long cnt) implements AgeingBandCount {
        @Override public String getState() { return state; }
        @Override public int getBand() { return band; }
        @Override public long getCnt() { return cnt; }
    }

    @Test
    void ageingRollsStatesIntoOpsBucketsAndBands() {
        when(shipmentRepository.ageingByStateAndBand(null, 7200L, 14400L, 28800L)).thenReturn(List.of(
                new Band("AT_AIRPORT", 0, 3),           // IN_TRANSIT, <2h
                new Band("DEST_HUB_PROCESSING", 3, 2),  // IN_TRANSIT, >8h
                new Band("DROP_ASSIGNED", 1, 4),        // OUT_FOR_DELIVERY, 2-4h
                new Band("DELIVERY_FAILED", 3, 1)));    // EXCEPTIONS, >8h

        AdminOrderSummaryServiceImpl svc = new AdminOrderSummaryServiceImpl(shipmentRepository);
        ShipmentAgeingStats stats = svc.ageing(null);

        assertThat(stats.total()).isEqualTo(10);
        assertThat(stats.bands()).containsExactly("<2h", "2-4h", "4-8h", "8h+");
        assertThat(stats.byBucket().get("IN_TRANSIT")).containsExactly(3L, 0L, 0L, 2L);
        assertThat(stats.byBucket().get("OUT_FOR_DELIVERY")).containsExactly(0L, 4L, 0L, 0L);
        assertThat(stats.byBucket().get("EXCEPTIONS")).containsExactly(0L, 0L, 0L, 1L);
        // Terminal states are excluded by the query, so DELIVERED never appears.
        assertThat(stats.byBucket()).doesNotContainKey("DELIVERED");
        assertThat(stats.bandTotals()).containsExactly(3L, 4L, 0L, 3L);
    }

    @Test
    void cityScopeUsesTheCityScopedQuery() {
        when(shipmentRepository.countByStateForCity("DEL"))
                .thenReturn(List.of(new Count(ShipmentState.BOOKED, 2)));
        AdminOrderSummaryServiceImpl svc = new AdminOrderSummaryServiceImpl(shipmentRepository);

        assertThat(svc.summary("DEL").total()).isEqualTo(2);
        verify(shipmentRepository, times(1)).countByStateForCity("DEL");
    }
}
