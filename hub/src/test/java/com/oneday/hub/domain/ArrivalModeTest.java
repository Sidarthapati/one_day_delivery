package com.oneday.hub.domain;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.hub.service.exception.UndeterminedArrivalException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArrivalModeTest {

    @Test
    void originStatesMapToVan() {
        assertThat(ArrivalMode.fromState(ShipmentState.HANDED_TO_PICKUP_VAN)).isEqualTo(ArrivalMode.VAN); // VAN_MEETING
        assertThat(ArrivalMode.fromState(ShipmentState.RETURNED_TO_HUB)).isEqualTo(ArrivalMode.VAN);      // HUB_RETURN
        assertThat(ArrivalMode.fromState(ShipmentState.AT_ORIGIN_HUB)).isEqualTo(ArrivalMode.VAN);        // re-scan
    }

    @Test
    void selfDropAndAirportMap() {
        assertThat(ArrivalMode.fromState(ShipmentState.AWAITING_SELF_DROP)).isEqualTo(ArrivalMode.SELF_DROP);
        assertThat(ArrivalMode.fromState(ShipmentState.DISPATCHED_TO_HUB)).isEqualTo(ArrivalMode.AIRPORT);
        assertThat(ArrivalMode.fromState(ShipmentState.AT_DEST_HUB)).isEqualTo(ArrivalMode.AIRPORT);
    }

    @Test
    void notYetArrivedThrows() {
        // Still in the DA's hands — not a dock-arrival state.
        assertThatThrownBy(() -> ArrivalMode.fromState(ShipmentState.PICKED_UP))
                .isInstanceOf(UndeterminedArrivalException.class);
    }
}
