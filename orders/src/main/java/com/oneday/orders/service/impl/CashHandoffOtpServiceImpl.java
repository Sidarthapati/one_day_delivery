package com.oneday.orders.service.impl;

import com.oneday.orders.domain.CashHandoffOtp;
import com.oneday.orders.repository.CashHandoffOtpRepository;
import com.oneday.orders.service.CashHandoffOtpService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * @see CashHandoffOtpService
 * Mirrors {@code PickupOtpServiceImpl}: BCrypt(4) is adequate because the short TTL + single-use flag
 * are the real guarantee; cleartext is never stored.
 */
@Service
class CashHandoffOtpServiceImpl implements CashHandoffOtpService {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(4);
    private static final SecureRandom RANDOM = new SecureRandom();
    // ponytail: fixed 15-min TTL — a cash handoff happens face-to-face at the station; a config knob if ops needs one.
    private static final long TTL_MINUTES = 15;

    private final CashHandoffOtpRepository otps;

    CashHandoffOtpServiceImpl(CashHandoffOtpRepository otps) {
        this.otps = otps;
    }

    @Override
    @Transactional
    public String generate(UUID depositId) {
        otps.deleteByDepositId(depositId);          // idempotent re-entry: one active code per deposit
        String otp = String.format("%04d", RANDOM.nextInt(10_000));
        CashHandoffOtp record = new CashHandoffOtp();
        record.setDepositId(depositId);
        record.setOtpHash(ENCODER.encode(otp));
        record.setExpiresAt(Instant.now().plus(TTL_MINUTES, ChronoUnit.MINUTES));
        record.setUsed(false);
        otps.save(record);
        return otp;
    }

    @Override
    @Transactional
    public void verify(UUID depositId, String otp) {
        // Pessimistic lock: two concurrent verifies can't both see used=false and both succeed.
        CashHandoffOtp record = otps.findByDepositIdWithLock(depositId)
                .orElseThrow(() -> new OtpVerificationException("No active handoff code for this deposit"));
        if (record.isUsed()) {
            throw new OtpVerificationException("This handoff code has already been used");
        }
        if (record.getExpiresAt().isBefore(Instant.now())) {
            throw new OtpVerificationException("This handoff code has expired — the DA should re-declare the deposit");
        }
        if (otp == null || !ENCODER.matches(otp.trim(), record.getOtpHash())) {
            throw new OtpVerificationException("That handoff code is incorrect");
        }
        record.setUsed(true);
        otps.save(record);
    }
}
