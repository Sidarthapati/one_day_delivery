package com.oneday.orders.service;

import com.oneday.common.domain.enums.ShipmentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CustomerVisibleStateMapper")
class CustomerVisibleStateMapperTest {

    private final CustomerVisibleStateMapper mapper = new CustomerVisibleStateMapper();

    // Completeness invariant: every internal state maps to a non-blank customer-facing label, so
    // the tracking API / "Your Bookings" list can never surface a raw enum or blank for any state.
    @Test
    void everyState_hasNonBlankLabel() {
        for (ShipmentState state : ShipmentState.values()) {
            assertThat(mapper.labelFor(state)).as("label for %s", state).isNotBlank();
        }
    }

    // Spot-check the wording against the design doc for a few headline states.
    @Test
    void labels_matchDesignDocWording() {
        assertThat(mapper.labelFor(ShipmentState.BOOKED)).isEqualTo("Order confirmed");
        assertThat(mapper.labelFor(ShipmentState.DROPPED)).isEqualTo("Delivered");
        assertThat(mapper.labelFor(ShipmentState.CANCELLED)).isEqualTo("Cancelled");
    }
}
