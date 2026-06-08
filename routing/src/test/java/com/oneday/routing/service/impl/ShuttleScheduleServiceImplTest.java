package com.oneday.routing.service.impl;

import com.oneday.routing.config.RoutingProperties;
import com.oneday.routing.domain.CityFleetConfig;
import com.oneday.routing.domain.CityLogisticsNode;
import com.oneday.routing.domain.LogisticsNodeKind;
import com.oneday.routing.events.CronEventProducer;
import com.oneday.routing.repository.CityFleetConfigRepository;
import com.oneday.routing.repository.CityLogisticsNodeRepository;
import com.oneday.routing.service.TravelMatrixService;
import com.oneday.routing.service.model.ShuttleTimetable;
import com.oneday.routing.service.model.TravelMatrix;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShuttleScheduleServiceImplTest {

    @Mock CityLogisticsNodeRepository logisticsNodeRepository;
    @Mock CityFleetConfigRepository fleetConfigRepository;
    @Mock TravelMatrixService travelMatrixService;
    @Mock CronEventProducer cronEventProducer;

    RoutingProperties properties;
    ShuttleScheduleServiceImpl service;

    private final UUID cityId = UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2026, 6, 10);

    @BeforeEach
    void setUp() {
        properties = new RoutingProperties(); // window 07:00–20:00, shuttle cadence 30
        service = new ShuttleScheduleServiceImpl(logisticsNodeRepository, fleetConfigRepository,
                travelMatrixService, cronEventProducer, properties);
    }

    private CityLogisticsNode node(LogisticsNodeKind kind) {
        return CityLogisticsNode.builder().id(UUID.randomUUID()).cityId(cityId)
                .kind(kind).lat(12.97).lon(77.61).name(kind.name()).build();
    }

    private void stubNodesAndLeg(long hubToAirportSeconds) {
        when(logisticsNodeRepository.findByCityIdAndKind(cityId, LogisticsNodeKind.HUB))
                .thenReturn(Optional.of(node(LogisticsNodeKind.HUB)));
        when(logisticsNodeRepository.findByCityIdAndKind(cityId, LogisticsNodeKind.AIRPORT))
                .thenReturn(Optional.of(node(LogisticsNodeKind.AIRPORT)));
        long[][] seconds = {{0, hubToAirportSeconds}, {hubToAirportSeconds, 0}};
        when(travelMatrixService.buildMatrix(any())).thenReturn(new TravelMatrix(List.of(), seconds));
    }

    @Test
    void timetable_30minCadenceOver07to20_gives27DeparturesAndArrivalEtas() {
        stubNodesAndLeg(1800); // 30 minutes
        when(fleetConfigRepository.findByCityId(cityId))
                .thenReturn(Optional.of(CityFleetConfig.builder().cityId(cityId).shuttleCadenceMinutes(30).build()));

        ShuttleTimetable t = service.timetable(cityId, date);

        // 07:00 .. 20:00 inclusive, every 30 min = 27 departures.
        assertThat(t.departureTimes()).hasSize(27);
        assertThat(t.departureTimes().get(0)).isEqualTo(LocalTime.of(7, 0));
        assertThat(t.departureTimes().get(t.departureTimes().size() - 1)).isEqualTo(LocalTime.of(20, 0));
        assertThat(t.hubToAirportMinutes()).isEqualTo(30);
        assertThat(t.arrivalFor(LocalTime.of(7, 0))).isEqualTo(LocalTime.of(7, 30));
    }

    @Test
    void timetable_fallsBackToPropertiesCadenceWhenNoFleetConfig() {
        stubNodesAndLeg(900); // 15 minutes
        when(fleetConfigRepository.findByCityId(cityId)).thenReturn(Optional.empty());

        ShuttleTimetable t = service.timetable(cityId, date);

        assertThat(t.hubToAirportMinutes()).isEqualTo(15);
        assertThat(t.departureTimes()).hasSize(27); // default cadence 30 → still 27
    }

    @Test
    void timetable_noAirport_returnsEmptyAndDoesNotCallOsrm() {
        when(logisticsNodeRepository.findByCityIdAndKind(cityId, LogisticsNodeKind.HUB))
                .thenReturn(Optional.of(node(LogisticsNodeKind.HUB)));
        when(logisticsNodeRepository.findByCityIdAndKind(cityId, LogisticsNodeKind.AIRPORT))
                .thenReturn(Optional.empty());

        ShuttleTimetable t = service.timetable(cityId, date);

        assertThat(t.departureTimes()).isEmpty();
        verify(travelMatrixService, never()).buildMatrix(anyList());
    }

    @Test
    void publish_emitsShuttleScheduled() {
        stubNodesAndLeg(1800);
        when(fleetConfigRepository.findByCityId(cityId))
                .thenReturn(Optional.of(CityFleetConfig.builder().cityId(cityId).shuttleCadenceMinutes(30).build()));
        UUID planId = UUID.randomUUID();

        service.publish(cityId, date, planId);

        verify(cronEventProducer).emitShuttleScheduled(any(ShuttleTimetable.class), any());
    }
}
