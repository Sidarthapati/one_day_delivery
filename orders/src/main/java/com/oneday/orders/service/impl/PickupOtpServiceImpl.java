package com.oneday.orders.service.impl;

import com.oneday.orders.config.PickupOtpProperties;
import com.oneday.orders.domain.PickupOtp;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.repository.PickupOtpRepository;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.PickupOtpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * BCrypt-backed OTP service. Cleartext OTP is generated per request, never stored.
 *
 * <p>All methods are {@code @Transactional} so that concurrent verify/resend calls
 * are serialised at the DB level via the unique index on {@code pickup_otps.shipment_id}.</p>
 */
@Service
class PickupOtpServiceImpl implements PickupOtpService {

    private static final Logger log = LoggerFactory.getLogger(PickupOtpServiceImpl.class);

    // BCrypt cost 4 is adequate for 4-digit OTPs — the 10-min TTL and single-use flag
    // provide the primary security guarantee. Cost 10 adds ~100 ms per operation with
    // no meaningful security gain for short-lived codes.
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(4);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PickupOtpRepository otpRepository;
    private final ShipmentRepository shipmentRepository;
    private final PickupOtpProperties properties;

    PickupOtpServiceImpl(PickupOtpRepository otpRepository,
                         ShipmentRepository shipmentRepository,
                         PickupOtpProperties properties) {
        this.otpRepository      = otpRepository;
        this.shipmentRepository = shipmentRepository;
        this.properties         = properties;
    }

    @Override
    @Transactional
    public String generate(UUID shipmentId) {
        Shipment shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Shipment not found: " + shipmentId));

        // Delete any existing OTP for this shipment (idempotent re-entry guard).
        otpRepository.deleteByShipmentId(shipmentId);

        String otp = generateFourDigitOtp();

        PickupOtp record = new PickupOtp();
        record.setShipment(shipment);
        record.setOtpHash(ENCODER.encode(otp));
        record.setExpiresAt(Instant.now().plus(properties.getTtlMinutes(), ChronoUnit.MINUTES));
        record.setResendCount((short) 0);
        record.setUsed(false);
        otpRepository.save(record);

        log.debug("OTP generated for shipmentId={}", shipmentId);
        return otp;
    }

    @Override
    @Transactional
    public void verify(UUID shipmentId, String otp) {
        // Pessimistic lock prevents two concurrent verify calls from both seeing
        // used=false and both succeeding — the second blocks until the first commits.
        PickupOtp record = otpRepository.findByShipmentIdWithLock(shipmentId)
                .orElseThrow(() -> new OtpVerificationException(
                        "No active OTP found for shipment"));

        if (record.isUsed()) {
            throw new OtpVerificationException("OTP has already been used");
        }
        if (record.getExpiresAt().isBefore(Instant.now())) {
            throw new OtpVerificationException("OTP has expired");
        }
        if (!ENCODER.matches(otp, record.getOtpHash())) {
            throw new OtpVerificationException("OTP is incorrect");
        }

        record.setUsed(true);
        otpRepository.save(record);
        log.debug("OTP verified for shipmentId={}", shipmentId);
    }

    @Override
    @Transactional
    public String resend(UUID shipmentId) {
        PickupOtp existing = otpRepository.findByShipmentId(shipmentId)
                .orElseThrow(() -> new OtpVerificationException(
                        "No active OTP found for shipment; call generate first"));

        if (existing.getResendCount() >= properties.getMaxResendCount()) {
            throw new ResendLimitExceededException(
                    "Resend limit (" + properties.getMaxResendCount() + ") reached for shipment " + shipmentId);
        }

        short newResendCount = (short) (existing.getResendCount() + 1);

        // Delete the old row (unique index prevents inserting a new one while old exists).
        otpRepository.deleteByShipmentId(shipmentId);

        // Re-use the already-loaded shipment reference — avoids a second DB round-trip.
        Shipment shipment = existing.getShipment();

        String otp = generateFourDigitOtp();

        PickupOtp record = new PickupOtp();
        record.setShipment(shipment);
        record.setOtpHash(ENCODER.encode(otp));
        record.setExpiresAt(Instant.now().plus(properties.getTtlMinutes(), ChronoUnit.MINUTES));
        record.setResendCount(newResendCount);
        record.setUsed(false);
        otpRepository.save(record);

        log.debug("OTP resent (attempt {}/{}) for shipmentId={}",
                newResendCount, properties.getMaxResendCount(), shipmentId);
        return otp;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Returns a zero-padded 4-digit string, e.g. "0042" or "9871". */
    private static String generateFourDigitOtp() {
        int value = RANDOM.nextInt(10_000); // 0–9999
        return String.format("%04d", value);
    }
}
