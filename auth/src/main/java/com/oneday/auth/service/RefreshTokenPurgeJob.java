package com.oneday.auth.service;

import com.oneday.auth.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Nightly purge of expired refresh tokens. Every login mints a row, so without this the table grows
 * unbounded. Revoked-but-unexpired rows are intentionally retained (reuse detection) — only rows
 * past their expiry are deleted. Runs at 03:30 IST. Needs {@code @EnableScheduling}
 * (on {@code app/OneDayDeliveryApplication}).
 */
@Service
public class RefreshTokenPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenPurgeJob.class);

    private final RefreshTokenRepository repository;

    public RefreshTokenPurgeJob(RefreshTokenRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "${godspeed.refresh.purge-cron:0 30 3 * * *}", zone = "Asia/Kolkata")
    public void purgeExpired() {
        Instant now = Instant.now();
        int deleted = repository.deleteExpiredBefore(now);
        log.info("RefreshTokenPurgeJob: deleted {} expired refresh tokens (cutoff={})", deleted, now);
    }
}
