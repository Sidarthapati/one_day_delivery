package com.oneday.orders.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
public class ResilienceConfig {

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(ResilienceProperties props) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(props.getFailureRateThreshold())
                .minimumNumberOfCalls(props.getMinimumNumberOfCalls())
                .slidingWindowSize(props.getSlidingWindowSize())
                .build();
        return CircuitBreakerRegistry.of(config);
    }

    @Bean
    public TimeLimiterRegistry timeLimiterRegistry(ResilienceProperties props) {
        Map<String, TimeLimiterConfig> configs = new HashMap<>();
        configs.put("serviceability", TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(props.getServiceabilityTimeoutMs()))
                .build());
        configs.put("pricing", TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(props.getPricingTimeoutMs()))
                .build());
        configs.put("payment", TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(props.getPaymentTimeoutMs()))
                .build());
        return TimeLimiterRegistry.of(configs);
    }

    @Bean(name = "resilienceScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService resilienceScheduler() {
        return Executors.newScheduledThreadPool(4);
    }

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }
}
