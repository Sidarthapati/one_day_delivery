package com.oneday.routing.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.routing.config.RoutingProperties;
import com.oneday.routing.domain.CityFleetConfig;
import com.oneday.routing.domain.CityLogisticsNode;
import com.oneday.routing.domain.DaCronSchedule;
import com.oneday.routing.domain.LogisticsNodeKind;
import com.oneday.routing.domain.RoutePlan;
import com.oneday.routing.domain.RoutePlanStatus;
import com.oneday.routing.events.CronEventProducer;
import com.oneday.routing.repository.DaCronScheduleRepository;
import com.oneday.routing.repository.RoutePlanRepository;
import com.oneday.routing.service.GridDataAdapter;
import com.oneday.routing.service.model.DaTerritory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HubReturnScheduleServiceTest {

    @Mock GridDataAdapter gridDataAdapter;
    @Mock RoutePlanRepository routePlanRepository;
    @Mock DaCronScheduleRepository daCronScheduleRepository;
    @Mock CronEventProducer cronEventProducer;

    RoutingProperties properties;
    HubReturnScheduleService service;

    private final UUID cityId = UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2026, 6, 10);
    private final UUID da1 = UUID.randomUUID();
    private final UUID da2 = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        properties = new RoutingProperties();   // window 07:00–20:00
        service = new HubReturnScheduleService(gridDataAdapter, routePlanRepository,
                daCronScheduleRepository, cronEventProducer, properties, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-06-09T20:00:00Z"), ZoneOffset.UTC));
    }

    private CityFleetConfig fleet(Integer intervalMinutes) {
        return CityFleetConfig.builder().cityId(cityId).hubReturnIntervalMinutes(intervalMinutes).build();
    }

    private CityLogisticsNode hub() {
        return CityLogisticsNode.builder().id(UUID.randomUUID()).cityId(cityId)
                .kind(LogisticsNodeKind.HUB).lat(28.61).lon(77.20).name("Delhi Hub").build();
    }

    @Test
    void producesApprovedPlanWithHubCronPerDa_noVan() {
        CityLogisticsNode hub = hub();
        when(gridDataAdapter.getDaTerritories(cityId, date))
                .thenReturn(List.of(new DaTerritory(da1, List.of()), new DaTerritory(da2, List.of())));
        when(routePlanRepository.findByCityIdAndValidForDate(cityId, date)).thenReturn(List.of());

        RoutePlan plan = service.planHubReturn(cityId, date, fleet(180), hub);

        assertThat(plan.getStatus()).isEqualTo(RoutePlanStatus.APPROVED);
        assertThat(plan.getVansUsed()).isZero();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DaCronSchedule>> captor = ArgumentCaptor.forClass(List.class);
        verify(daCronScheduleRepository).saveAll(captor.capture());
        List<DaCronSchedule> crons = captor.getValue();
        assertThat(crons).hasSize(2);
        assertThat(crons).allSatisfy(c -> {
            assertThat(c.getVanId()).isNull();                   // no van in HUB_RETURN
            assertThat(c.getHexVertexId()).isEqualTo(hub.getId());
            assertThat(c.getCityId()).isEqualTo(cityId);
            assertThat(c.getMeetingTimes()).contains("10:00", "13:00", "16:00", "19:00");
        });

        // Every DA gets a DA_CRON_SCHEDULED carrying the HUB coordinates.
        verify(cronEventProducer, times(2)).emitDaCronScheduled(any(), eq(28.61), eq(77.20));
    }

    @Test
    void hubReturnTimes_defaultIntervalWhenNull() {
        // null interval → 180-min default → 10:00, 13:00, 16:00, 19:00 over the 07–20 window.
        assertThat(service.hubReturnTimes(HubReturnScheduleService.DEFAULT_INTERVAL_MINUTES))
                .containsExactly("10:00", "13:00", "16:00", "19:00");
    }

    @Test
    void hubReturnTimes_alwaysYieldsAtLeastOneSlot() {
        // Interval larger than the window still returns the shift-end slot rather than nothing.
        assertThat(service.hubReturnTimes(24 * 60)).containsExactly("20:00");
    }
}
