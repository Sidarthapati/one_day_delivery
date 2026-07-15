package com.oneday.airline.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * IST clock, mirroring M7's {@code ClockConfig} (M7-D-011) / M6's clock pattern. The scheduled
 * status poller (§13) and the reassignment engine need real "now"; every other M9 service still
 * takes an explicit {@code readyAt}/{@code flightDate} and never calls {@code Instant.now()} directly.
 */
// Explicit bean name — hub.config.ClockConfig and routing.config.ClockConfig both also default-name
// "clockConfig", and all three modules assemble in the app → ConflictingBeanDefinitionException
// without this (same fix routing already applies against hub).
@Configuration("airlineClockConfig")
public class ClockConfig {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.system(IST);
    }
}
