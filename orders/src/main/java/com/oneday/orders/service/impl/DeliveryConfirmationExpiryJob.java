package com.oneday.orders.service.impl;

import com.oneday.orders.service.DeliveryConfirmationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Flips stale PENDING delivery confirmations to EXPIRED. Cosmetic only — silence already counts as
 * accept, so an unexpired-but-unanswered prompt never blocks delivery; this just keeps the landing page
 * honest for a receiver who opens the link after the window.
 */
@Component
class DeliveryConfirmationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(DeliveryConfirmationExpiryJob.class);

    private final DeliveryConfirmationService service;

    DeliveryConfirmationExpiryJob(DeliveryConfirmationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${orders.delivery.expiry-sweep-ms:300000}")
    public void sweep() {
        int expired = service.expireStale();
        if (expired > 0) {
            log.debug("Expired {} stale delivery confirmations", expired);
        }
    }
}
