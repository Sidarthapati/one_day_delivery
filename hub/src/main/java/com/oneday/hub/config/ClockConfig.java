package com.oneday.hub.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * IST clock (M7-D-011). Every M7 service injects this {@link Clock} and never calls
 * {@code Instant.now()} directly, so a full hub day can be simulated in seconds by swapping in a
 * fixed/offset clock in tests (mirrors M6-D-013).
 *
 * <p>Explicit bean name ({@code "hubClockConfig"}) so this @Configuration doesn't collide on the
 * default {@code clockConfig} name with M6's {@link com.oneday.routing.config.ClockConfig} once both
 * modules are assembled in {@code app}. The {@link Clock} itself is {@code @ConditionalOnMissingBean}
 * so exactly one Clock exists regardless of module scan order (M6 and M7 both yield to an existing one).</p>
 */
@Configuration("hubClockConfig")
public class ClockConfig {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Bean
    @ConditionalOnMissingBean
    public Clock hubClock() {
        return Clock.system(IST);
    }
}
