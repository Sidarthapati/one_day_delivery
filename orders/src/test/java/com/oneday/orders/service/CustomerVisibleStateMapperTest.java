package com.oneday.orders.service;

import com.oneday.common.domain.enums.ShipmentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CustomerVisibleStateMapper")
class CustomerVisibleStateMapperTest {

    private final CustomerVisibleStateMapper mapper = new CustomerVisibleStateMapper();

    @ParameterizedTest(name = "{0} has a non-blank label")
    @EnumSource(ShipmentState.class)
    void everyState_hasNonBlankLabel(ShipmentState state) {
        assertThat(mapper.labelFor(state))
                .as("Label for %s", state)
                .isNotNull()
                .isNotBlank();
    }

    @Test
    void labelFor_booked_matchesDesignDoc() {
        assertThat(mapper.labelFor(ShipmentState.BOOKED)).isEqualTo("Order confirmed");
    }

    @Test
    void labelFor_dropped_matchesDesignDoc() {
        assertThat(mapper.labelFor(ShipmentState.DROPPED)).isEqualTo("Delivered");
    }

    @Test
    void labelFor_cancelled_matchesDesignDoc() {
        assertThat(mapper.labelFor(ShipmentState.CANCELLED)).isEqualTo("Cancelled");
    }

    @Test
    void labelFor_rtoCompleted_matchesDesignDoc() {
        assertThat(mapper.labelFor(ShipmentState.RTO_COMPLETED)).isEqualTo("Returned to sender");
    }

    @Test
    void labelFor_awaitingSelfDrop_matchesDesignDoc() {
        assertThat(mapper.labelFor(ShipmentState.AWAITING_SELF_DROP))
                .isEqualTo("Please bring your parcel to the origin hub");
    }

    @Test
    void labelFor_awaitingHubCollect_matchesDesignDoc() {
        assertThat(mapper.labelFor(ShipmentState.AWAITING_HUB_COLLECT))
                .isEqualTo("Your parcel is ready — collect from the hub");
    }
}
