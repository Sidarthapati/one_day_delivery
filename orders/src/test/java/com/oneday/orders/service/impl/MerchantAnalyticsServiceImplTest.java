package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.dto.MerchantAnalyticsResponse;
import com.oneday.orders.repository.AccountTotals;
import com.oneday.orders.repository.CityCount;
import com.oneday.orders.repository.OnTimeStat;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.repository.StateCount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantAnalyticsServiceImplTest {

    @Mock private ShipmentRepository shipments;

    private final UUID acct = UUID.randomUUID();

    private static StateCount state(ShipmentState s, long c) {
        return new StateCount() {
            public ShipmentState getState() { return s; }
            public long getCount() { return c; }
        };
    }

    private static AccountTotals totals(long gmv, long cod) {
        return new AccountTotals() {
            public long getGmvPaise() { return gmv; }
            public long getCodPaise() { return cod; }
        };
    }

    private static OnTimeStat onTime(long delivered, long on) {
        return new OnTimeStat() {
            public long getDelivered() { return delivered; }
            public long getOnTime() { return on; }
        };
    }

    private static CityCount city(String c, long n) {
        return new CityCount() {
            public String getCity() { return c; }
            public long getCount() { return n; }
        };
    }

    @Test
    void classifiesStatesAndComputesRates() {
        // 10 delivered (8 DROPPED + 2 HUB_COLLECTED), 3 in transit, 2 cancelled, 1 RTO → total 16.
        when(shipments.countByStateForAccount(eq(acct), any())).thenReturn(List.of(
                state(ShipmentState.DROPPED, 8),
                state(ShipmentState.HUB_COLLECTED, 2),
                state(ShipmentState.AT_DEST_HUB, 3),
                state(ShipmentState.CANCELLED, 2),
                state(ShipmentState.RTO_COMPLETED, 1)));
        when(shipments.sumTotalsForAccount(eq(acct), any())).thenReturn(totals(1_600_000, 500_000));
        when(shipments.onTimeForAccount(eq(acct), any(), any())).thenReturn(onTime(10, 9));
        when(shipments.destinationSplitForAccount(eq(acct), any())).thenReturn(List.of(
                city("DEL", 9), city("BLR", 7)));

        MerchantAnalyticsServiceImpl svc = new MerchantAnalyticsServiceImpl(shipments);
        MerchantAnalyticsResponse r = svc.forAccount(acct, 30);

        assertThat(r.totalShipments()).isEqualTo(16);
        assertThat(r.delivered()).isEqualTo(10);
        assertThat(r.inTransit()).isEqualTo(3);
        assertThat(r.cancelled()).isEqualTo(2);
        assertThat(r.rto()).isEqualTo(1);
        // delivery rate = delivered / (total - cancelled) = 10/14 = 71%
        assertThat(r.deliveryRatePct()).isEqualTo(71);
        // on-time = 9/10 = 90%
        assertThat(r.onTimePct()).isEqualTo(90);
        assertThat(r.gmvPaise()).isEqualTo(1_600_000);
        assertThat(r.codValuePaise()).isEqualTo(500_000);
        assertThat(r.avgShipmentPaise()).isEqualTo(100_000); // 1_600_000 / 16
        assertThat(r.topDestinations()).hasSize(2);
        assertThat(r.topDestinations().get(0).city()).isEqualTo("DEL");
        assertThat(r.windowDays()).isEqualTo(30);
    }

    @Test
    void nullPercentagesWhenNothingToRate() {
        // No shipments at all.
        when(shipments.countByStateForAccount(eq(acct), any())).thenReturn(List.of());
        when(shipments.sumTotalsForAccount(eq(acct), any())).thenReturn(totals(0, 0));
        when(shipments.onTimeForAccount(eq(acct), any(), any())).thenReturn(onTime(0, 0));
        when(shipments.destinationSplitForAccount(eq(acct), any())).thenReturn(List.of());

        MerchantAnalyticsResponse r = new MerchantAnalyticsServiceImpl(shipments).forAccount(acct, null);

        assertThat(r.totalShipments()).isZero();
        assertThat(r.deliveryRatePct()).isNull();  // nothing rateable
        assertThat(r.onTimePct()).isNull();         // nothing delivered-with-ETA
        assertThat(r.avgShipmentPaise()).isZero();
        assertThat(r.windowDays()).isNull();        // all-time
    }

    @Test
    void allCancelledIsNotRateableButOnTimeStillNull() {
        when(shipments.countByStateForAccount(eq(acct), any())).thenReturn(List.of(
                state(ShipmentState.CANCELLED, 4)));
        when(shipments.sumTotalsForAccount(eq(acct), any())).thenReturn(totals(0, 0));
        when(shipments.onTimeForAccount(eq(acct), any(), any())).thenReturn(onTime(0, 0));
        when(shipments.destinationSplitForAccount(eq(acct), any())).thenReturn(List.of());

        MerchantAnalyticsResponse r = new MerchantAnalyticsServiceImpl(shipments).forAccount(acct, 7);

        assertThat(r.totalShipments()).isEqualTo(4);
        assertThat(r.cancelled()).isEqualTo(4);
        assertThat(r.deliveryRatePct()).isNull(); // total - cancelled = 0 → not rateable
    }
}
