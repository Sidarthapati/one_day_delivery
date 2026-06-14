package com.oneday.pricing.service.impl;

import com.oneday.common.port.dto.CostFloorQuery;
import com.oneday.common.port.dto.CostFloorResult;
import com.oneday.pricing.domain.CostingParams;
import com.oneday.pricing.repository.CostingParamsRepository;
import com.oneday.pricing.service.NoRateConfiguredException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CostingServiceImplTest {

    private final CostingParamsRepository repo = mock(CostingParamsRepository.class);
    private final CostingServiceImpl service = new CostingServiceImpl(repo);

    private CostingParams blr() {
        CostingParams p = new CostingParams();
        p.setCity("BLR");
        p.setVersion("v1.0");
        p.setStatus("ACTIVE");
        p.setDaCostPerShiftPaise(120000);
        p.setShiftHours(8.0);
        p.setUtilisationPct(70);
        p.setAvgParcelsPerShift(40);
        p.setVanCostPerRunPaise(300000);
        p.setAvgParcelsPerVanRun(120);
        p.setHubCostPerParcelPaise(1500);
        p.setAirlineCostPerParcelPaise(4000);
        return p;
    }

    @Test
    void costFloorSumsSharesAtUtilisation() {
        when(repo.findFirstByCityAndStatus("BLR", "ACTIVE")).thenReturn(Optional.of(blr()));

        CostFloorResult r = service.computeCostFloor(new CostFloorQuery("BLR"));

        // DA: 120000 / (40 * 0.70 = 28) = 4286; van: 300000/120 = 2500; hub 1500; airline 4000.
        assertThat(r.breakdown()).containsEntry("da_pickup", 4286L);
        assertThat(r.breakdown()).containsEntry("van", 2500L);
        assertThat(r.costFloorPaise()).isEqualTo(4286 + 2500 + 1500 + 4000);
        assertThat(r.costingVersion()).isEqualTo("v1.0");
    }

    @Test
    void normalisesCityNameAndFailsWhenMissing() {
        when(repo.findFirstByCityAndStatus("BLR", "ACTIVE")).thenReturn(Optional.of(blr()));
        // "Bengaluru" normalises to BLR.
        assertThat(service.computeCostFloor(new CostFloorQuery("Bengaluru")).city()).isEqualTo("BLR");

        when(repo.findFirstByCityAndStatus("XXX", "ACTIVE")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.computeCostFloor(new CostFloorQuery("XXX")))
                .isInstanceOf(NoRateConfiguredException.class);
    }
}
