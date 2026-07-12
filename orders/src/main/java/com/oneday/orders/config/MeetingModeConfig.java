package com.oneday.orders.config;

import com.oneday.common.domain.MeetingMode;
import com.oneday.common.port.CityMeetingModePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;
import java.util.UUID;

/**
 * Fallback {@link CityMeetingModePort} for M4's isolated module tests. Orders cannot depend on routing,
 * which owns the real adapter (over {@code city_fleet_config}) — absent it, every city reads as
 * VAN_MEETING, so {@code DaEventsConsumer} keeps mapping DROP_ASSIGNED/DROP_COLLECTED to the van states.
 * In the assembled app routing's {@code @Primary CityMeetingModeAdapter} is present, so this backs off.
 */
@Configuration
public class MeetingModeConfig {

    @Bean
    @ConditionalOnMissingBean(CityMeetingModePort.class)
    CityMeetingModePort vanMeetingModePort() {
        return new CityMeetingModePort() {
            @Override
            public MeetingMode modeFor(UUID cityId) {
                return MeetingMode.VAN_MEETING;
            }

            @Override
            public Optional<HubLocation> hubLocation(UUID cityId) {
                return Optional.empty();
            }
        };
    }
}
