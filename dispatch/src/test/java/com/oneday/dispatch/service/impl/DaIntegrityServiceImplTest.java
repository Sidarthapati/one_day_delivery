package com.oneday.dispatch.service.impl;

import com.oneday.common.port.DaDirectoryPort;
import com.oneday.common.port.DaDirectoryPort.DaContact;
import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.DaStatus;
import com.oneday.dispatch.dto.response.DaIntegritySummary;
import com.oneday.dispatch.repository.DaGpsPingRepository;
import com.oneday.dispatch.repository.DaPingIntegrityAggregate;
import com.oneday.dispatch.repository.DaStatusRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Triage: a mock-provider fix → RED, some flagged fixes → AMBER, clean → GREEN; worst-trust first, city-scoped. */
class DaIntegrityServiceImplTest {

    private final DaGpsPingRepository pingRepository = mock(DaGpsPingRepository.class);
    private final DaStatusRepository daStatusRepository = mock(DaStatusRepository.class);
    private final DaDirectoryPort daDirectory = mock(DaDirectoryPort.class);
    private final DispatchProperties props = new DispatchProperties();
    private final DaIntegrityServiceImpl service =
            new DaIntegrityServiceImpl(pingRepository, daStatusRepository, daDirectory, props);

    private final UUID cityA = UUID.randomUUID();
    private final UUID cityB = UUID.randomUUID();

    private DaPingIntegrityAggregate agg(UUID da, long total, long flagged, int maxRisk,
                                         long mocked, long velocity, long skew) {
        DaPingIntegrityAggregate a = mock(DaPingIntegrityAggregate.class);
        when(a.getDaId()).thenReturn(da);
        when(a.getTotal()).thenReturn(total);
        when(a.getFlagged()).thenReturn(flagged);
        when(a.getMaxRisk()).thenReturn(maxRisk);
        when(a.getMockedCount()).thenReturn(mocked);
        when(a.getVelocityCount()).thenReturn(velocity);
        when(a.getSkewCount()).thenReturn(skew);
        return a;
    }

    private void wireCity(UUID da, UUID city) {
        DaStatus s = new DaStatus();
        s.setDaId(da);
        s.setCityId(city);
        when(daStatusRepository.findByDaId(da)).thenReturn(Optional.of(s));
    }

    @Test
    void triagesAndOrdersWorstFirst() {
        UUID clean = UUID.randomUUID(), amber = UUID.randomUUID(), red = UUID.randomUUID();
        wireCity(clean, cityA);
        wireCity(amber, cityA);
        wireCity(red, cityA);
        // Build each projection mock standalone — a when(...) inside List.of(...) trips UnfinishedStubbing.
        DaPingIntegrityAggregate cleanAgg = agg(clean, 100, 0, 0, 0, 0, 0);
        DaPingIntegrityAggregate amberAgg = agg(amber, 100, 3, 40, 0, 3, 0);   // velocity flags, no mock → AMBER
        DaPingIntegrityAggregate redAgg = agg(red, 100, 5, 60, 2, 0, 0);        // mock provider fixes → RED
        when(pingRepository.aggregateIntegrityByDa(any(), any()))
                .thenReturn(List.of(cleanAgg, amberAgg, redAgg));
        when(daDirectory.contactsFor(any())).thenReturn(Map.of(
                clean, new DaContact("Clean", "+91"),
                amber, new DaContact("Amber", "+92"),
                red, new DaContact("Red", "+93")));

        List<DaIntegritySummary> out = service.summariesForDate(LocalDate.parse("2026-08-31"), null);

        assertThat(out).hasSize(3);
        assertThat(out.get(0).trustLevel()).isEqualTo("RED");
        assertThat(out.get(1).trustLevel()).isEqualTo("AMBER");
        assertThat(out.get(2).trustLevel()).isEqualTo("GREEN");
        assertThat(out.get(0).mockedPings()).isEqualTo(2);
    }

    @Test
    void cityScopeExcludesOtherCities() {
        UUID mine = UUID.randomUUID(), other = UUID.randomUUID();
        wireCity(mine, cityA);
        wireCity(other, cityB);
        DaPingIntegrityAggregate mineAgg = agg(mine, 10, 0, 0, 0, 0, 0);
        DaPingIntegrityAggregate otherAgg = agg(other, 10, 0, 0, 0, 0, 0);
        when(pingRepository.aggregateIntegrityByDa(any(), any()))
                .thenReturn(List.of(mineAgg, otherAgg));
        when(daDirectory.contactsFor(any())).thenReturn(Map.of(mine, new DaContact("Mine", "+91")));

        List<DaIntegritySummary> out = service.summariesForDate(LocalDate.parse("2026-08-31"), cityA);

        assertThat(out).extracting(DaIntegritySummary::daId).containsExactly(mine);
    }
}
