package com.oneday.dispatch.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Wires M5's configuration. Scheduling itself is enabled application-wide by the app module's
 * {@code @EnableScheduling}, so the {@code @Scheduled} jobs here just need to be beans.
 */
@Configuration
@EnableConfigurationProperties(DispatchProperties.class)
public class DispatchConfig {
}
