package com.oneday.routing.service.impl;

import com.oneday.common.domain.MeetingMode;
import com.oneday.common.port.CityMeetingModePort;
import com.oneday.routing.domain.LogisticsNodeKind;
import com.oneday.routing.repository.CityFleetConfigRepository;
import com.oneday.routing.repository.CityLogisticsNodeRepository;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Routing-side implementation of {@link CityMeetingModePort}: reads the per-city M6 gate off
 * {@code city_fleet_config} and the hub coordinates off {@code city_logistics_node}. A city with
 * no fleet config is treated as {@link MeetingMode#VAN_MEETING} (the safe default — nothing changes
 * for cities the gate was never set on).
 */
@Component
@Primary   // wins over dispatch's VAN_MEETING fallback in the assembled app
public class CityMeetingModeAdapter implements CityMeetingModePort {

    private final CityFleetConfigRepository fleetRepository;
    private final CityLogisticsNodeRepository nodeRepository;

    public CityMeetingModeAdapter(CityFleetConfigRepository fleetRepository,
                                  CityLogisticsNodeRepository nodeRepository) {
        this.fleetRepository = fleetRepository;
        this.nodeRepository = nodeRepository;
    }

    @Override
    public MeetingMode modeFor(UUID cityId) {
        return fleetRepository.findByCityId(cityId)
                .map(c -> c.getMeetingMode() != null ? c.getMeetingMode() : MeetingMode.VAN_MEETING)
                .orElse(MeetingMode.VAN_MEETING);
    }

    @Override
    public Optional<HubLocation> hubLocation(UUID cityId) {
        return nodeRepository.findByCityIdAndKind(cityId, LogisticsNodeKind.HUB)
                .map(n -> new HubLocation(n.getLat(), n.getLon()));
    }
}
