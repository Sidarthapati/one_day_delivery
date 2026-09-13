package com.oneday.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Advances in-flight background-verification checks to their verdicts and moves cleared candidates into
 * the approval queue. Runs every {@code bgv.poll-interval-ms} (default 30s). A reviewer can also force a
 * poll per candidate via the BGV refresh endpoint. Needs {@code @EnableScheduling}
 * (on {@code app/OneDayDeliveryApplication}).
 */
@Service
public class BgvPollJob {

    private static final Logger log = LoggerFactory.getLogger(BgvPollJob.class);

    private final BgvService bgvService;

    public BgvPollJob(BgvService bgvService) {
        this.bgvService = bgvService;
    }

    @Scheduled(fixedDelayString = "${bgv.poll-interval-ms:30000}")
    public void pollPending() {
        try {
            bgvService.pollAllPending();
        } catch (Exception e) {
            log.warn("BgvPollJob run failed: {}", e.getMessage());
        }
    }
}
