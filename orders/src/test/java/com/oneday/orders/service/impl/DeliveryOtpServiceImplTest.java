package com.oneday.orders.service.impl;

import com.oneday.orders.config.DeliveryOtpProperties;
import com.oneday.orders.domain.DeliveryOtp;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.repository.DeliveryOtpRepository;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.DeliveryOtpService;
import com.oneday.orders.service.DevOtpRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Drop-side OTP mirror; exercises generate/verify/resend branches with real BCrypt + mocked repos. */
@SuppressWarnings("unchecked")
class DeliveryOtpServiceImplTest {

    private final DeliveryOtpRepository otpRepo = mock(DeliveryOtpRepository.class);
    private final ShipmentRepository shipmentRepo = mock(ShipmentRepository.class);
    private final DeliveryOtpProperties props = new DeliveryOtpProperties(); // ttl=30, maxResend=3
    private final DevOtpRegistry registry = new DevOtpRegistry();
    private final ObjectProvider<DevOtpRegistry> provider = mock(ObjectProvider.class);
    private final BCryptPasswordEncoder enc = new BCryptPasswordEncoder(4);

    private DeliveryOtpServiceImpl service() {
        doAnswer(inv -> { ((Consumer<DevOtpRegistry>) inv.getArgument(0)).accept(registry); return null; })
                .when(provider).ifAvailable(any());
        return new DeliveryOtpServiceImpl(otpRepo, shipmentRepo, props, provider);
    }

    @Test
    void generate_savesHashAndPeeksDevRegistry() {
        UUID id = UUID.randomUUID();
        when(shipmentRepo.findById(id)).thenReturn(Optional.of(new Shipment()));
        String otp = service().generate(id);
        assertThat(otp).hasSize(4);
        assertThat(registry.getDelivery(id)).isEqualTo(otp);
        verify(otpRepo).deleteByShipmentId(id);
        verify(otpRepo).save(any(DeliveryOtp.class));
    }

    @Test
    void generate_shipmentNotFound_throws() {
        UUID id = UUID.randomUUID();
        when(shipmentRepo.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().generate(id))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Shipment not found");
    }

    @Test
    void verify_matching_marksUsed() {
        UUID id = UUID.randomUUID();
        DeliveryOtp rec = otp("1234", false, Instant.now().plus(5, ChronoUnit.MINUTES), (short) 0);
        when(otpRepo.findByShipmentIdWithLock(id)).thenReturn(Optional.of(rec));
        service().verify(id, "1234");
        assertThat(rec.isUsed()).isTrue();
        verify(otpRepo).save(rec);
    }

    @Test
    void verify_noRecord_throws() {
        UUID id = UUID.randomUUID();
        when(otpRepo.findByShipmentIdWithLock(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().verify(id, "1234"))
                .isInstanceOf(DeliveryOtpService.OtpVerificationException.class);
    }

    @Test
    void verify_alreadyUsed_throws() {
        UUID id = UUID.randomUUID();
        when(otpRepo.findByShipmentIdWithLock(id)).thenReturn(Optional.of(
                otp("1234", true, Instant.now().plus(5, ChronoUnit.MINUTES), (short) 0)));
        assertThatThrownBy(() -> service().verify(id, "1234"))
                .hasMessageContaining("already been used");
    }

    @Test
    void verify_expired_throws() {
        UUID id = UUID.randomUUID();
        when(otpRepo.findByShipmentIdWithLock(id)).thenReturn(Optional.of(
                otp("1234", false, Instant.now().minus(1, ChronoUnit.MINUTES), (short) 0)));
        assertThatThrownBy(() -> service().verify(id, "1234")).hasMessageContaining("expired");
    }

    @Test
    void verify_wrongCode_throws() {
        UUID id = UUID.randomUUID();
        when(otpRepo.findByShipmentIdWithLock(id)).thenReturn(Optional.of(
                otp("1234", false, Instant.now().plus(5, ChronoUnit.MINUTES), (short) 0)));
        assertThatThrownBy(() -> service().verify(id, "9999")).hasMessageContaining("incorrect");
    }

    @Test
    void resend_underLimit_rotatesCode() {
        UUID id = UUID.randomUUID();
        DeliveryOtp existing = otp("1234", false, Instant.now().plus(5, ChronoUnit.MINUTES), (short) 1);
        existing.setShipment(new Shipment());
        when(otpRepo.findByShipmentId(id)).thenReturn(Optional.of(existing));
        String otp = service().resend(id);
        assertThat(otp).hasSize(4);
        verify(otpRepo).deleteByShipmentId(id);
        verify(otpRepo).save(any(DeliveryOtp.class));
    }

    @Test
    void resend_noExisting_throws() {
        UUID id = UUID.randomUUID();
        when(otpRepo.findByShipmentId(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().resend(id))
                .isInstanceOf(DeliveryOtpService.OtpVerificationException.class);
    }

    @Test
    void resend_atLimit_throws() {
        UUID id = UUID.randomUUID();
        when(otpRepo.findByShipmentId(id)).thenReturn(Optional.of(
                otp("1234", false, Instant.now().plus(5, ChronoUnit.MINUTES), (short) 3))); // == maxResend
        assertThatThrownBy(() -> service().resend(id))
                .isInstanceOf(DeliveryOtpService.ResendLimitExceededException.class);
    }

    private DeliveryOtp otp(String code, boolean used, Instant expiresAt, short resend) {
        DeliveryOtp rec = new DeliveryOtp();
        rec.setOtpHash(enc.encode(code));
        rec.setUsed(used);
        rec.setExpiresAt(expiresAt);
        rec.setResendCount(resend);
        return rec;
    }
}
