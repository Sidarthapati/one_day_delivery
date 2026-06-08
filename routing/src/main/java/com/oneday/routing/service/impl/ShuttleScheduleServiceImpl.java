package com.oneday.routing.service.impl;

import com.oneday.routing.config.RoutingProperties;
import com.oneday.routing.domain.CityFleetConfig;
import com.oneday.routing.domain.CityLogisticsNode;
import com.oneday.routing.domain.LogisticsNodeKind;
import com.oneday.routing.domain.StopNodeKind;
import com.oneday.routing.events.CronEventProducer;
import com.oneday.routing.repository.CityFleetConfigRepository;
import com.oneday.routing.repository.CityLogisticsNodeRepository;
import com.oneday.routing.service.ShuttleScheduleService;
import com.oneday.routing.service.TravelMatrixService;
import com.oneday.routing.service.model.RoutingNode;
import com.oneday.routing.service.model.ShuttleTimetable;
import com.oneday.routing.service.model.TravelMatrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds the periodic hub↔airport timetable (§9). Cadence comes from {@code city_fleet_config}
 * (falling back to {@link RoutingProperties}); the hub→airport leg time is one OSRM {@code /table}
 * call over the 2-node set {hub, airport}. Departures run from the window start up to and including
 * the window end at the configured cadence. v1 cadence is fixed; when M9 lands it becomes
 * flight-cutoff-driven via {@code FlightCutoffPort} (Q1).
 */
@Service
class ShuttleScheduleServiceImpl implements ShuttleScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ShuttleScheduleServiceImpl.class);

    private final CityLogisticsNodeRepository logisticsNodeRepository;
    private final CityFleetConfigRepository fleetConfigRepository;
    private final TravelMatrixService travelMatrixService;
    private final CronEventProducer cronEventProducer;
    private final RoutingProperties properties;

    ShuttleScheduleServiceImpl(CityLogisticsNodeRepository logisticsNodeRepository,
                               CityFleetConfigRepository fleetConfigRepository,
                               TravelMatrixService travelMatrixService,
                               CronEventProducer cronEventProducer,
                               RoutingProperties properties) {
        this.logisticsNodeRepository = logisticsNodeRepository;
        this.fleetConfigRepository = fleetConfigRepository;
        this.travelMatrixService = travelMatrixService;
        this.cronEventProducer = cronEventProducer;
        this.properties = properties;
    }

    @Override
    public ShuttleTimetable timetable(UUID cityId, LocalDate date) {
        CityLogisticsNode hub = logisticsNodeRepository.findByCityIdAndKind(cityId, LogisticsNodeKind.HUB)
                .orElseThrow(() -> new IllegalStateException("No HUB logistics node for cityId=" + cityId));
        Optional<CityLogisticsNode> airport = logisticsNodeRepository.findByCityIdAndKind(cityId, LogisticsNodeKind.AIRPORT);
        if (airport.isEmpty()) {
            log.warn("No AIRPORT logistics node for cityId={} — empty shuttle timetable", cityId);
            return new ShuttleTimetable(cityId, date, List.of(), 0);
        }

        int hubToAirportMinutes = hubToAirportMinutes(hub, airport.get());
        int cadence = cadenceMinutes(cityId);
        List<LocalTime> departures = departureTimes(cadence);
        return new ShuttleTimetable(cityId, date, departures, hubToAirportMinutes);
    }

    @Override
    public ShuttleTimetable publish(UUID cityId, LocalDate date, UUID routePlanId) {
        ShuttleTimetable timetable = timetable(cityId, date);
        if (!timetable.departureTimes().isEmpty()) {
            cronEventProducer.emitShuttleScheduled(timetable, routePlanId);
        }
        return timetable;
    }

    private int hubToAirportMinutes(CityLogisticsNode hub, CityLogisticsNode airport) {
        List<RoutingNode> nodes = List.of(
                RoutingNode.hub(hub.getId(), hub.getLat(), hub.getLon()),
                new RoutingNode(1, StopNodeKind.MEETING_VERTEX,
                        airport.getId(), airport.getLat(), airport.getLon(), 0, 0, 0));
        TravelMatrix matrix = travelMatrixService.buildMatrix(nodes);
        long seconds = matrix.travelSeconds()[0][1];
        return (int) Math.ceil(seconds / 60.0);
    }

    private int cadenceMinutes(UUID cityId) {
        return fleetConfigRepository.findByCityId(cityId)
                .map(CityFleetConfig::getShuttleCadenceMinutes)
                .filter(c -> c > 0)
                .orElse(properties.getShuttle().getCadenceMinutes());
    }

    // Departures from window start up to and including window end, every `cadence` minutes.
    private List<LocalTime> departureTimes(int cadence) {
        List<LocalTime> times = new ArrayList<>();
        LocalTime start = LocalTime.of(properties.getWindow().getStartHour(), 0);
        LocalTime end = LocalTime.of(properties.getWindow().getEndHour(), 0);
        for (LocalTime t = start; !t.isAfter(end); t = t.plusMinutes(cadence)) {
            times.add(t);
        }
        return times;
    }
}
