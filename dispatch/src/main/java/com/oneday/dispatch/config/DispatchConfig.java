package com.oneday.dispatch.config;

import com.oneday.common.domain.MeetingMode;
import com.oneday.common.port.CityMeetingModePort;
import com.oneday.dispatch.service.AdjacentDaProvider;
import com.oneday.dispatch.service.OsrmRoutingPort;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Wires M5's configuration. Scheduling itself is enabled application-wide by the app module's
 * {@code @EnableScheduling}, so the {@code @Scheduled} jobs here just need to be beans.
 */
@Configuration
@EnableConfigurationProperties(DispatchProperties.class)
public class DispatchConfig {

    /**
     * Placeholder routing port until PR #14 wires the real OSRM HTTP client + circuit breaker.
     * Reports "unavailable" so the cron-feasibility engine takes its conservative haversine fallback
     * on borderline checks. {@code @ConditionalOnMissingBean} lets the real implementation replace
     * it transparently once it lands.
     */
    @Bean
    @ConditionalOnMissingBean(OsrmRoutingPort.class)
    OsrmRoutingPort unavailableOsrmRoutingPort() {
        return waypoints -> OptionalLong.empty();
    }

    /**
     * No-op cross-territory candidate source until M3 exposes tile adjacency. With no candidates the
     * engine defers an infeasible pickup rather than spilling over — the sanctioned v1 behaviour.
     */
    @Bean
    @ConditionalOnMissingBean(AdjacentDaProvider.class)
    AdjacentDaProvider noAdjacentDaProvider() {
        return (cityId, originTileId, date) -> List.of();
    }

    /**
     * A plain in-process {@link MeterRegistry} for {@code DispatchMetrics} when the app has no metrics
     * auto-configuration (we no longer pull the actuator starter). If the app does provide one (e.g. a
     * composite registry), that bean wins and this backs off.
     */
    @Bean
    @ConditionalOnMissingBean(MeterRegistry.class)
    MeterRegistry simpleMeterRegistry() {
        return new SimpleMeterRegistry();
    }

    /**
     * Fallback for M5's isolated module tests: dispatch cannot depend on routing, which owns the real
     * {@link CityMeetingModePort} (over {@code city_fleet_config}). Absent that, every city reads as
     * VAN_MEETING — so {@code HubDeliveryFeedConsumer} no-ops. In the assembled app routing's
     * {@code @Primary CityMeetingModeAdapter} is present, so this backs off.
     */
    @Bean
    @ConditionalOnMissingBean(CityMeetingModePort.class)
    CityMeetingModePort vanMeetingModePort() {
        return new CityMeetingModePort() {
            @Override
            public MeetingMode modeFor(java.util.UUID cityId) {
                return MeetingMode.VAN_MEETING;
            }

            @Override
            public Optional<HubLocation> hubLocation(java.util.UUID cityId) {
                return Optional.empty();
            }
        };
    }
}
