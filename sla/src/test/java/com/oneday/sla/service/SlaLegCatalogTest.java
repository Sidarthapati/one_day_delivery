package com.oneday.sla.service;

import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.domain.enums.SlaLegType;
import com.oneday.sla.config.SlaProperties;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SlaLegCatalogTest {

    private SlaLegCatalog catalog() {
        SlaProperties props = new SlaProperties();
        Map<SlaLegType, Integer> legs = new EnumMap<>(SlaLegType.class);
        legs.put(SlaLegType.FIRST_MILE, 180);
        legs.put(SlaLegType.ORIGIN_HUB, 60);
        props.setLegs(legs);
        return new SlaLegCatalog(props);
    }

    @Test
    void intercityPlanHasAllSevenLegs_sameCityCollapsesToThree() {
        SlaLegCatalog c = catalog();
        assertThat(c.plan(DeliveryType.INTERCITY)).hasSize(7)
                .startsWith(SlaLegType.FIRST_MILE).endsWith(SlaLegType.LAST_MILE);
        assertThat(c.plan(DeliveryType.SAME_CITY))
                .containsExactly(SlaLegType.FIRST_MILE, SlaLegType.ORIGIN_HUB, SlaLegType.LAST_MILE);
    }

    @Test
    void mapsStatesToTheirLiveLeg() {
        SlaLegCatalog c = catalog();
        assertThat(c.activeLeg(ShipmentState.BOOKED)).contains(SlaLegType.FIRST_MILE);
        assertThat(c.activeLeg(ShipmentState.AT_ORIGIN_HUB)).contains(SlaLegType.ORIGIN_HUB);
        assertThat(c.activeLeg(ShipmentState.DEPARTED)).contains(SlaLegType.AIR);
        assertThat(c.activeLeg(ShipmentState.LANDED)).contains(SlaLegType.DEST_AIRPORT);
        assertThat(c.activeLeg(ShipmentState.HANDED_TO_DROP_VAN)).contains(SlaLegType.LAST_MILE);
        assertThat(c.activeLeg(ShipmentState.COLLECTED_FROM_HUB)).contains(SlaLegType.LAST_MILE);
        assertThat(c.activeLeg(ShipmentState.DROPPED)).isEmpty();
    }

    @Test
    void classifiesTerminalAndExceptionStates() {
        SlaLegCatalog c = catalog();
        assertThat(c.isTerminalSuccess(ShipmentState.DROPPED)).isTrue();
        assertThat(c.isTerminalSuccess(ShipmentState.HUB_COLLECTED)).isTrue();
        assertThat(c.isTerminalSuccess(ShipmentState.DELIVERY_FAILED)).isFalse();
        assertThat(c.isException(ShipmentState.PICKUP_FAILED)).isTrue();
        assertThat(c.isException(ShipmentState.RTO_IN_TRANSIT)).isTrue();
        assertThat(c.isException(ShipmentState.DROPPED)).isFalse();
        assertThat(c.budgetMinutes(SlaLegType.FIRST_MILE)).isEqualTo(180);
    }
}
