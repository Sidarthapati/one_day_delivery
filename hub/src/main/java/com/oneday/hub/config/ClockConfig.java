package com.oneday.hub.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * IST clock (M7-D-011). Every M7 service injects this {@link Clock} and never calls
 * {@code Instant.now()} directly, so a full hub day can be simulated in seconds by swapping in a
 * fixed/offset clock in tests (mirrors M6-D-013).
 */
@Configuration
public class ClockConfig {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Bean
    public Clock hubClock() {
        return Clock.system(IST);
    }
}
