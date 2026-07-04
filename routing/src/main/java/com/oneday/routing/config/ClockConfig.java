package com.oneday.routing.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Single source of "now" for M6 (M6-D-013). Every service injects this {@link Clock} rather than
 * calling {@code Instant.now()} so the simulation harness (§21) can drive a 16h day in seconds with
 * a fixed/accelerated clock. {@code @ConditionalOnMissingBean} lets tests supply their own.
 */
// Explicit bean name — hub.config.ClockConfig also default-names "clockConfig", and both
// modules assemble in the app → ConflictingBeanDefinitionException without this.
@Configuration("routingClockConfig")
public class ClockConfig {

    /** IST — the operating window (07:00–20:00) and all cron times are wall-clock India time. */
    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.system(IST);
    }
}
