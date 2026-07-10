package com.oneday.orders.service.impl;

import com.oneday.orders.config.DeliveryOtpProperties;
import com.oneday.orders.domain.DeliveryOtp;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.repository.DeliveryOtpRepository;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.DeliveryOtpService;
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
 * BCrypt-backed delivery OTP service — drop-side mirror of {@code PickupOtpServiceImpl}. Cleartext is
 * generated per request, never stored. All methods are {@code @Transactional} so concurrent
 * verify/resend calls serialise on the unique index over {@code delivery_otps.shipment_id}.
 */
@Service
class DeliveryOtpServiceImpl implements DeliveryOtpService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryOtpServiceImpl.class);

    // Cost 4: adequate for short-lived 4-digit codes; the TTL + single-use flag are the real guard.
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(4);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DeliveryOtpRepository otpRepository;
    private final ShipmentRepository shipmentRepository;
    private final DeliveryOtpProperties properties;

    DeliveryOtpServiceImpl(DeliveryOtpRepository otpRepository,
                           ShipmentRepository shipmentRepository,
                           DeliveryOtpProperties properties) {
        this.otpRepository      = otpRepository;
        this.shipmentRepository = shipmentRepository;
        this.properties         = properties;
    }

    @Override
    @Transactional
    public String generate(UUID shipmentId) {
        Shipment shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new IllegalArgumentException("Shipment not found: " + shipmentId));

        otpRepository.deleteByShipmentId(shipmentId);   // idempotent re-entry guard

        String otp = generateFourDigitOtp();

        DeliveryOtp record = new DeliveryOtp();
        record.setShipment(shipment);
        record.setOtpHash(ENCODER.encode(otp));
        record.setExpiresAt(Instant.now().plus(properties.getTtlMinutes(), ChronoUnit.MINUTES));
        record.setResendCount((short) 0);
        record.setUsed(false);
        otpRepository.save(record);

        log.debug("Delivery OTP generated for shipmentId={}", shipmentId);
        return otp;
    }

    @Override
    @Transactional
    public void verify(UUID shipmentId, String otp) {
        DeliveryOtp record = otpRepository.findByShipmentIdWithLock(shipmentId)
                .orElseThrow(() -> new OtpVerificationException("No active delivery OTP found for shipment"));

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
        log.debug("Delivery OTP verified for shipmentId={}", shipmentId);
    }

    @Override
    @Transactional
    public String resend(UUID shipmentId) {
        DeliveryOtp existing = otpRepository.findByShipmentId(shipmentId)
                .orElseThrow(() -> new OtpVerificationException(
                        "No active delivery OTP found for shipment; call generate first"));

        if (existing.getResendCount() >= properties.getMaxResendCount()) {
            throw new ResendLimitExceededException(
                    "Resend limit (" + properties.getMaxResendCount() + ") reached for shipment " + shipmentId);
        }

        short newResendCount = (short) (existing.getResendCount() + 1);
        Shipment shipment = existing.getShipment();   // reuse the loaded reference

        otpRepository.deleteByShipmentId(shipmentId);

        String otp = generateFourDigitOtp();

        DeliveryOtp record = new DeliveryOtp();
        record.setShipment(shipment);
        record.setOtpHash(ENCODER.encode(otp));
        record.setExpiresAt(Instant.now().plus(properties.getTtlMinutes(), ChronoUnit.MINUTES));
        record.setResendCount(newResendCount);
        record.setUsed(false);
        otpRepository.save(record);

        log.debug("Delivery OTP resent (attempt {}/{}) for shipmentId={}",
                newResendCount, properties.getMaxResendCount(), shipmentId);
        return otp;
    }

    /** Returns a zero-padded 4-digit string, e.g. "0042" or "9871". */
    private static String generateFourDigitOtp() {
        return String.format("%04d", RANDOM.nextInt(10_000));
    }
}
