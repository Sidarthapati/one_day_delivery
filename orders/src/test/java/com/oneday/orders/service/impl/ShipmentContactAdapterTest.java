package com.oneday.orders.service.impl;

import com.oneday.orders.domain.Address;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShipmentContactAdapterTest {

    @Test
    void prefersGranularTrioAndDropsBlanks() {
        Address a = new Address();
        a.setHouseFloor("12, 2nd Floor");
        a.setBuildingStreet("");          // blank → dropped
        a.setAreaLocality("Indiranagar");
        a.setLandmark("near Metro");
        a.setCity("Bengaluru");
        a.setPincode("560038");
        assertThat(ShipmentContactAdapter.compose(a))
                .isEqualTo("12, 2nd Floor, Indiranagar, near Metro, Bengaluru, 560038");
    }

    @Test
    void fallsBackToLine1Line2WhenGranularBlank() {
        Address a = new Address();
        a.setLine1("221B Baker Street");
        a.setLine2("Marylebone");
        a.setCity("Bengaluru");
        a.setPincode("560001");
        assertThat(ShipmentContactAdapter.compose(a))
                .isEqualTo("221B Baker Street, Marylebone, Bengaluru, 560001");
    }

    @Test
    void nullAddressComposesToNull() {
        assertThat(ShipmentContactAdapter.compose(null)).isNull();
    }
}
