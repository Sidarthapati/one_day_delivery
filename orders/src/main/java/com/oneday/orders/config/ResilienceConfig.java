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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
        // NOTE: registry.timeLimiter("name") uses the registry's DEFAULT config, NOT the
        // same-named entry from a config map. So we must pre-create each instance WITH its
        // config; otherwise every limiter silently falls back to resilience4j's 1s default
        // (which the booking service then hits against the remote dev DB).
        TimeLimiterRegistry registry = TimeLimiterRegistry.ofDefaults();
        registry.timeLimiter("serviceability", TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(props.getServiceabilityTimeoutMs()))
                .build());
        registry.timeLimiter("pricing", TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(props.getPricingTimeoutMs()))
                .build());
        registry.timeLimiter("payment", TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(props.getPaymentTimeoutMs()))
                .build());
        return registry;
    }

    @Bean(name = "resilienceScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService resilienceScheduler() {
        // TimeLimiter-wrapped serviceability/pricing/payment calls run on this pool. Bulk upload
        // prices rows in parallel, so each concurrent quote needs a slot here — 8 gives room for
        // parallel bulk pricing without starving interactive bookings.
        return Executors.newScheduledThreadPool(8);
    }

    /**
     * Bounded pool for pricing bulk-upload rows concurrently (each row does a read-only
     * serviceability + quote). Capped so a large sheet can't exhaust the DB connection pool or
     * monopolise the resilience scheduler; overflow runs on the calling thread (back-pressure).
     */
    @Bean(name = "bulkPricingExecutor", destroyMethod = "shutdown")
    public ExecutorService bulkPricingExecutor() {
        int threads = 8;
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(256),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }
}
