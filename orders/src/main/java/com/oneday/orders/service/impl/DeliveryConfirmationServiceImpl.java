package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.kafka.EventPublisher;
import com.oneday.common.kafka.EventStreams;
import com.oneday.common.kafka.events.ReceiverRejectedEvent;
import com.oneday.common.port.NotificationPort;
import com.oneday.common.port.dto.NotificationEventType;
import com.oneday.common.port.dto.NotificationRequest;
import com.oneday.orders.config.OrdersDeliveryProperties;
import com.oneday.orders.domain.DeliveryConfirmation;
import com.oneday.orders.domain.DeliveryConfirmationStatus;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.repository.DeliveryConfirmationRepository;
import com.oneday.orders.repository.ParcelOrderRepository;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.DeliveryConfirmationService;
import com.oneday.orders.service.DeliveryConfirmationView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * @see DeliveryConfirmationService
 */
@Service
class DeliveryConfirmationServiceImpl implements DeliveryConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryConfirmationServiceImpl.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    // States in which the accept/reject window has closed — the parcel is delivered, returned, held, or
    // cancelled, so a response can no longer change anything (FIX-5: don't act on a concluded parcel).
    private static final Set<ShipmentState> CONCLUDED = EnumSet.of(
            ShipmentState.DROPPED, ShipmentState.HUB_COLLECTED, ShipmentState.RTO_INITIATED,
            ShipmentState.RTO_COMPLETED, ShipmentState.HELD_AT_HUB, ShipmentState.CANCELLED);

    private final ShipmentRepository shipmentRepository;
    private final ParcelOrderRepository parcelOrderRepository;
    private final DeliveryConfirmationRepository confirmationRepository;
    private final NotificationPort notificationPort;
    private final EventPublisher eventPublisher;
    private final OrdersDeliveryProperties props;

    DeliveryConfirmationServiceImpl(ShipmentRepository shipmentRepository,
                                    ParcelOrderRepository parcelOrderRepository,
                                    DeliveryConfirmationRepository confirmationRepository,
                                    NotificationPort notificationPort,
                                    EventPublisher eventPublisher,
                                    OrdersDeliveryProperties props) {
        this.shipmentRepository = shipmentRepository;
        this.parcelOrderRepository = parcelOrderRepository;
        this.confirmationRepository = confirmationRepository;
        this.notificationPort = notificationPort;
        this.eventPublisher = eventPublisher;
        this.props = props;
    }

    @Override
    // Called from an AFTER_COMMIT listener (DeliveryConfirmationTrigger, which swallows any throw), where
    // no transaction is active — REQUIRES_NEW so the confirmation row + notification commit, and a failed
    // send rolls the row back instead of leaving a live prompt that would suppress a later retry.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void promptOnDeparture(UUID shipmentId) {
        Shipment s = shipmentRepository.findById(shipmentId).orElse(null);
        if (s == null) {
            return;
        }
        if (s.getReceiverEmail() == null || s.getReceiverEmail().isBlank()) {
            log.debug("Shipment {} has no receiver email — skipping delivery confirmation", shipmentId);
            return;
        }
        String baseUrl = props.getCustomerLandingBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("No customer-landing-base-url configured — skipping delivery confirmation for {}", shipmentId);
            return;
        }
        // Idempotent: one live prompt per shipment (intercity DEPARTED first, then a same-city
        // sorted-for-delivery trigger no-ops here).
        if (confirmationRepository.findFirstByShipmentIdAndStatus(shipmentId,
                DeliveryConfirmationStatus.PENDING).isPresent()) {
            return;
        }

        Eta eta = computeEta(s);
        String token = mintToken();
        Instant now = Instant.now();

        DeliveryConfirmation c = new DeliveryConfirmation();
        c.setShipmentId(shipmentId);
        c.setAttemptNo(1);
        c.setTokenHash(sha256(token));
        c.setStatus(DeliveryConfirmationStatus.PENDING);
        c.setEta(eta.instant());
        c.setEtaShift(eta.shift());
        c.setEtaDay(eta.day());
        c.setChannel("EMAIL");
        c.setSentAt(now);
        c.setExpiresAt(now.plus(props.getConfirmationTtlMinutes(), ChronoUnit.MINUTES));
        confirmationRepository.save(c);

        String link = baseUrl.replaceAll("/+$", "") + "/d/" + token;
        // Send inside the tx: a failure rolls back the just-saved PENDING row (REQUIRES_NEW), so a failed
        // send never leaves a live prompt. The trigger catches the throw so transit is never affected.
        notificationPort.send(new NotificationRequest(
                NotificationEventType.RECEIVER_CONFIRM,
                s.getReceiverEmail(), null,
                Map.of(
                        "shipment_ref", s.getShipmentRef() != null ? s.getShipmentRef() : "",
                        "receiver_name", s.getReceiverName() != null ? s.getReceiverName() : "there",
                        "eta_text", eta.text(),
                        "link", link),
                null));
        log.info("Delivery confirmation sent for shipment {} (ETD {})", shipmentId, eta.text());
    }

    @Override
    @Transactional(readOnly = true)
    public DeliveryConfirmationView getByToken(String token) {
        return toView(load(token));
    }

    @Override
    @Transactional
    public DeliveryConfirmationView accept(String token) {
        DeliveryConfirmation c = load(token);
        if (respondable(c)) {
            c.setStatus(DeliveryConfirmationStatus.ACCEPTED);
            c.setRespondedAt(Instant.now());
            confirmationRepository.save(c);
        }
        return toView(c);
    }

    @Override
    @Transactional
    public DeliveryConfirmationView reject(String token, String targetShift) {
        String shift = normaliseShift(targetShift);
        DeliveryConfirmation c = load(token);
        if (respondable(c)) {
            c.setStatus(DeliveryConfirmationStatus.REJECTED);
            c.setResponseShift(shift);
            c.setRespondedAt(Instant.now());
            confirmationRepository.save(c);
            publishRejected(c.getShipmentId(), shift);
        }
        return toView(c);
    }

    @Override
    @Transactional
    public int expireStale() {
        List<DeliveryConfirmation> stale = confirmationRepository
                .findByStatusAndExpiresAtBefore(DeliveryConfirmationStatus.PENDING, Instant.now());
        for (DeliveryConfirmation c : stale) {
            c.setStatus(DeliveryConfirmationStatus.EXPIRED);
        }
        confirmationRepository.saveAll(stale);
        return stale.size();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private void publishRejected(UUID shipmentId, String shift) {
        shipmentRepository.findById(shipmentId).ifPresent(s -> {
            Double lat = s.getDestAddress() != null ? s.getDestAddress().getLatitude() : null;
            Double lon = s.getDestAddress() != null ? s.getDestAddress().getLongitude() : null;
            String orderRef = orderRefFor(s.getOrderId());
            eventPublisher.publish(EventStreams.DELIVERY_CONFIRMATIONS, new ReceiverRejectedEvent(
                    shipmentId, s.getShipmentRef(), s.getOrderId(), orderRef, shift, lat, lon, s.getDestTileId()));
        });
    }

    private String orderRefFor(UUID orderId) {
        if (orderId == null) {
            return null;
        }
        return parcelOrderRepository.findById(orderId).map(o -> o.getOrderRef()).orElse(null);
    }

    private DeliveryConfirmation load(String token) {
        return confirmationRepository.findByTokenHash(sha256(token))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown confirmation link"));
    }

    private DeliveryConfirmationView toView(DeliveryConfirmation c) {
        Shipment s = shipmentRepository.findById(c.getShipmentId()).orElse(null);
        String ref = s != null ? s.getShipmentRef() : null;
        String receiver = s != null ? s.getReceiverName() : null;
        boolean concluded = s != null && CONCLUDED.contains(s.getState());
        boolean canRespond = c.getStatus() == DeliveryConfirmationStatus.PENDING
                && c.getExpiresAt().isAfter(Instant.now())
                && !concluded;
        return new DeliveryConfirmationView(ref, receiver, c.getStatus().name(),
                c.getEtaDay(), c.getEtaShift(), etaText(c.getEtaDay(), c.getEtaShift(), c.getEta()),
                c.getResponseShift(), canRespond);
    }

    /** The parcel is still en route (not delivered/returned/held/cancelled), so a response can still act. */
    private boolean stillActionable(UUID shipmentId) {
        return shipmentRepository.findById(shipmentId)
                .map(s -> !CONCLUDED.contains(s.getState()))
                .orElse(false);
    }

    /** A confirmation can be answered only while PENDING, not past its expiry, and the parcel not concluded. */
    private boolean respondable(DeliveryConfirmation c) {
        return c.getStatus() == DeliveryConfirmationStatus.PENDING
                && c.getExpiresAt() != null && c.getExpiresAt().isAfter(Instant.now())
                && stillActionable(c.getShipmentId());
    }

    /**
     * Project the ETD. Prefer the parcel's promised delivery ETA (set at booking via {@code EtaPort}, so
     * it already accounts for the flight leg); fall back to a now()+hub+last-mile heuristic only when no
     * promised ETA is stored yet. The shift is derived from the projected time in both branches.
     */
    private Eta computeEta(Shipment s) {
        ZoneId zone = ZoneId.of(props.getZone());
        Instant projected = s.getEtaPromised() != null
                ? s.getEtaPromised()
                : Instant.now()
                    .plus(props.getHubProcessingMinutes(), ChronoUnit.MINUTES)
                    .plus(props.getLastMileWindowMinutes(), ChronoUnit.MINUTES);
        ZonedDateTime projectedZ = projected.atZone(zone);
        LocalDate today = LocalDate.now(zone);
        LocalDateTime cutoffToday = LocalDateTime.of(today, props.getSameDayCutoff());
        String shift = projectedZ.toLocalTime().isBefore(LocalTime.NOON) ? "SHIFT_1" : "SHIFT_2";
        boolean sameDay = !projectedZ.toLocalDate().isAfter(today)
                && !projectedZ.toLocalDateTime().isAfter(cutoffToday);
        String day = sameDay ? "TODAY" : "NEXT_DAY";
        return new Eta(projected, day, shift, etaText(day, shift, projected));
    }

    private String etaText(String day, String shift, Instant eta) {
        String when = "NEXT_DAY".equals(day) ? "tomorrow" : "today";
        String part = "SHIFT_1".equals(shift) ? "morning" : "afternoon";
        return when + " " + part;
    }

    private String normaliseShift(String targetShift) {
        if (targetShift == null) {
            return null;
        }
        String s = targetShift.trim().toUpperCase();
        if (!s.equals("SHIFT_1") && !s.equals("SHIFT_2")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "target_shift must be SHIFT_1 or SHIFT_2");
        }
        return s;
    }

    private static String mintToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record Eta(Instant instant, String day, String shift, String text) {}
}
