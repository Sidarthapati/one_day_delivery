package com.oneday.orders.service;

import com.oneday.orders.repository.IdempotencyKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Nightly job that deletes expired idempotency keys.
 *
 * <p>Keys older than {@link com.oneday.orders.config.IdempotencyProperties#getTtl()} are
 * no longer replayable — a client reusing an expired key is treated as a fresh request.
 * Purging expired rows keeps {@code idempotency_keys} small and the
 * {@code idx_idempotency_expires} index lean.</p>
 *
 * <p>Runs at 02:00 IST every day. The exact time is low-traffic by design;
 * adjust via {@code orders.idempotency.purge-cron} in {@code application.yml} if needed.</p>
 *
 * <p>Requires {@code @EnableScheduling} on a {@code @Configuration} class (provided by
 * {@code app/OneDayDeliveryApplication}) for this method to be invoked by the scheduler.</p>
 */
@Service
public class IdempotencyKeyPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyPurgeJob.class);

    private final IdempotencyKeyRepository repository;

    public IdempotencyKeyPurgeJob(IdempotencyKeyRepository repository) {
        this.repository = repository;
    }

    /**
     * Deletes all {@code idempotency_keys} rows whose {@code expires_at} is in the past.
     * The underlying repository method is {@code @Modifying @Transactional} — the delete
     * runs in its own transaction and commits before this method returns.
     */
    @Scheduled(cron = "${orders.idempotency.purge-cron:0 0 2 * * *}", zone = "Asia/Kolkata")
    public void purgeExpiredKeys() {
        Instant now = Instant.now();
        int deleted = repository.deleteExpired(now);
        log.info("IdempotencyKeyPurgeJob: deleted {} expired keys (cutoff={})", deleted, now);
    }
}
