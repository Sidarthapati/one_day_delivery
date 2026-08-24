package com.oneday.orders.service;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.service.OrderStatusReducer.OrderStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderStatusReducerTest {

    @Test
    void empty_isEmpty() {
        assertThat(OrderStatusReducer.reduce(List.of())).isEqualTo(OrderStatus.EMPTY);
        assertThat(OrderStatusReducer.reduce(null)).isEqualTo(OrderStatus.EMPTY);
    }

    @Test
    void allBooked_isBooked() {
        assertThat(OrderStatusReducer.reduce(List.of(ShipmentState.BOOKED, ShipmentState.BOOKED)))
                .isEqualTo(OrderStatus.BOOKED);
    }

    @Test
    void allDelivered_isDelivered() {
        assertThat(OrderStatusReducer.reduce(List.of(ShipmentState.DROPPED, ShipmentState.HUB_COLLECTED)))
                .isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    void allCancelled_isCancelled() {
        assertThat(OrderStatusReducer.reduce(List.of(ShipmentState.CANCELLED, ShipmentState.CANCELLED)))
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void allReturned_isReturned() {
        assertThat(OrderStatusReducer.reduce(List.of(ShipmentState.RTO_COMPLETED)))
                .isEqualTo(OrderStatus.RETURNED);
    }

    @Test
    void someDeliveredSomeActive_isPartiallyDelivered() {
        assertThat(OrderStatusReducer.reduce(List.of(ShipmentState.DROPPED, ShipmentState.IN_TAKEOFF_BAG)))
                .isEqualTo(OrderStatus.PARTIALLY_DELIVERED);
    }

    @Test
    void deliveredAndCancelled_allTerminal_isMixed() {
        assertThat(OrderStatusReducer.reduce(List.of(ShipmentState.DROPPED, ShipmentState.CANCELLED)))
                .isEqualTo(OrderStatus.MIXED);
    }

    @Test
    void movingThroughChain_isInProgress() {
        assertThat(OrderStatusReducer.reduce(List.of(ShipmentState.BOOKED, ShipmentState.PICKED_UP)))
                .isEqualTo(OrderStatus.IN_PROGRESS);
    }

    @Test
    void cancelledPlusActive_noneDelivered_isInProgress() {
        assertThat(OrderStatusReducer.reduce(List.of(ShipmentState.CANCELLED, ShipmentState.AT_ORIGIN_HUB)))
                .isEqualTo(OrderStatus.IN_PROGRESS);
    }
}
