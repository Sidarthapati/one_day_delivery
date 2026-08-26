package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.port.NotificationPort;
import com.oneday.common.port.dto.NotificationEventType;
import com.oneday.common.port.dto.NotificationRequest;
import com.oneday.orders.domain.B2bAccount;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.dto.ReviseEtaResponse;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.ShipmentEtaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

@Service
class ShipmentEtaServiceImpl implements ShipmentEtaService {

    private static final Logger log = LoggerFactory.getLogger(ShipmentEtaServiceImpl.class);
    private static final DateTimeFormatter ETA_FMT =
            DateTimeFormatter.ofPattern("d MMM, h:mm a").withZone(ZoneId.of("Asia/Kolkata"));

    private final ShipmentRepository shipments;
    private final B2bAccountRepository accounts;
    private final NotificationPort notificationPort;

    /** ponytail: a small grace so a tiny slip doesn't spam the customer. Tune per SLA sensitivity. */
    private final long graceMinutes;

    ShipmentEtaServiceImpl(ShipmentRepository shipments, B2bAccountRepository accounts,
                           NotificationPort notificationPort,
                           @Value("${orders.eta.delay-grace-minutes:15}") long graceMinutes) {
        this.shipments = shipments;
        this.accounts = accounts;
        this.notificationPort = notificationPort;
        this.graceMinutes = graceMinutes;
    }

    @Override
    @Transactional
    public ReviseEtaResponse reviseEta(String shipmentRef, Instant newEta, String reason, String actorUserId,
                                       String cityScope) {
        // ponytail: no row lock — ETA revision is rare and ops-driven; the @Transactional dirty-check is enough.
        Shipment s = shipments.findByShipmentRef(shipmentRef)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found: " + shipmentRef));

        // City scope (station manager): only a parcel touching their city; else 404, not 403 — a ref
        // outside scope reads as "not found", matching the ops read/cancel rule.
        if (cityScope != null && !cityScope.equals(s.getOriginCity()) && !cityScope.equals(s.getDestCity())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found: " + shipmentRef);
        }

        Instant promised = s.getEtaPromised();
        s.setEtaUpdated(newEta);
        // Dirty-checking persists the change; no explicit save needed inside the transaction.

        boolean delayed = promised != null && newEta.isAfter(promised.plus(Duration.ofMinutes(graceMinutes)));
        boolean notified = false;
        if (delayed) {
            notified = notifyCustomer(s, promised, newEta);
        }
        // Sanitize caller-supplied strings before logging — strip CR/LF so they can't forge log lines.
        log.info("ETA revised for {} → {} (promised {}, delayed={}, notified={}, by {}, reason={})",
                forLog(shipmentRef), newEta, promised, delayed, notified, actorUserId, forLog(reason));
        return new ReviseEtaResponse(shipmentRef, promised, newEta, delayed, notified);
    }

    /** Neutralize CR/LF in caller-supplied text so it can't inject forged lines into the log. */
    private static String forLog(String s) {
        return s == null ? null : s.replaceAll("[\\r\\n]", "_");
    }

    /** Send the delay mail to whoever booked: the B2B account (billing contact) or the retail sender. */
    private boolean notifyCustomer(Shipment s, Instant promised, Instant newEta) {
        String email;
        String phone;
        UUID accountId;
        if (s.getCustomerType() == CustomerType.B2B && s.getB2bAccountId() != null) {
            B2bAccount acc = accounts.findById(s.getB2bAccountId()).orElse(null);
            if (acc != null) {
                email = acc.getBillingEmail();
                phone = acc.getSupportPhone();
                accountId = acc.getId();
            } else {
                email = s.getSenderEmail();
                phone = s.getSenderPhone();
                accountId = null;
            }
        } else {
            email = s.getSenderEmail();
            phone = s.getSenderPhone();
            accountId = null;
        }
        if ((email == null || email.isBlank()) && (phone == null || phone.isBlank())) {
            log.warn("ETA delayed for {} but no customer contact to notify", s.getShipmentRef());
            return false;
        }
        notificationPort.send(new NotificationRequest(
                NotificationEventType.SHIPMENT_DELAYED, email, phone,
                Map.of("shipment_ref", s.getShipmentRef(),
                        "new_eta", ETA_FMT.format(newEta),
                        "original_eta", promised != null ? ETA_FMT.format(promised) : "the original estimate"),
                accountId));
        return true;
    }
}
