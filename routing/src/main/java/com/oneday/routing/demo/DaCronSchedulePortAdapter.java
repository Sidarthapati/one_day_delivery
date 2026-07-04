package com.oneday.routing.demo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.common.port.DaCronSchedulePort;
import com.oneday.routing.domain.RoutePlan;
import com.oneday.routing.domain.RoutePlanStatus;
import com.oneday.routing.repository.DaCronScheduleRepository;
import com.oneday.routing.repository.RoutePlanRepository;
import com.oneday.routing.service.GridDataAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * M6-side implementation of {@link DaCronSchedulePort}: exposes the date's APPROVED plan's per-DA cron
 * rendezvous (vertex coords + the van each DA meets) so M5's demo can seat DAs on their real meeting
 * vertices. Demo-only ({@code @Profile("!prod")}).
 */
@Component
@Profile("!prod")
class DaCronSchedulePortAdapter implements DaCronSchedulePort {

    private static final Logger log = LoggerFactory.getLogger(DaCronSchedulePortAdapter.class);

    private final RoutePlanRepository planRepository;
    private final DaCronScheduleRepository cronRepository;
    private final GridDataAdapter gridDataAdapter;
    private final ObjectMapper objectMapper;

    DaCronSchedulePortAdapter(RoutePlanRepository planRepository, DaCronScheduleRepository cronRepository,
                              GridDataAdapter gridDataAdapter, ObjectMapper objectMapper) {
        this.planRepository = planRepository;
        this.cronRepository = cronRepository;
        this.gridDataAdapter = gridDataAdapter;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<DaCron> cronsForCity(UUID cityId, LocalDate date) {
        RoutePlan plan = planRepository
                .findByCityIdAndValidForDateAndStatus(cityId, date, RoutePlanStatus.APPROVED).stream()
                .findFirst().orElse(null);
        if (plan == null) {
            return List.of();
        }
        Map<UUID, double[]> vertexCoords = gridDataAdapter.vertexCoords(cityId);
        return cronRepository.findByRoutePlanId(plan.getId()).stream().map(c -> {
            double[] xy = vertexCoords.getOrDefault(c.getHexVertexId(), new double[]{0, 0});
            return new DaCron(c.getDaId(), c.getVanId(), c.getHexVertexId(), xy[0], xy[1],
                    parseMeetingTimes(c.getMeetingTimes()));
        }).toList();
    }

    /** {@code meeting_times} is a JSON array of ISO LocalTime strings, e.g. {@code ["06:00","10:00"]}. */
    private List<String> parseMeetingTimes(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.debug("Could not parse meeting_times '{}': {}", json, e.getMessage());
            return List.of();
        }
    }
}
