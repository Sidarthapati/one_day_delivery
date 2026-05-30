package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.config.PickupOtpProperties;
import com.oneday.orders.domain.PickupOtp;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.repository.PickupOtpRepository;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.PickupOtpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PickupOtpServiceTest {

    @Mock private PickupOtpRepository otpRepository;
    @Mock private ShipmentRepository shipmentRepository;

    private PickupOtpProperties properties;
    private PickupOtpService service;

    private static final UUID SHIPMENT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        properties = new PickupOtpProperties(); // uses defaults: ttlMinutes=10, maxResendCount=3
        service = new PickupOtpServiceImpl(otpRepository, shipmentRepository, properties);
    }

    // -------------------------------------------------------------------------
    // generate()
    // -------------------------------------------------------------------------

    @Test
    void generate_createsOtpRowAndReturnsFourDigitString() {
        when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment()));
        when(otpRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String otp = service.generate(SHIPMENT_ID);

        assertThat(otp).matches("\\d{4}");
        verify(otpRepository).deleteByShipmentId(SHIPMENT_ID);
        ArgumentCaptor<PickupOtp> captor = ArgumentCaptor.forClass(PickupOtp.class);
        verify(otpRepository).save(captor.capture());
        PickupOtp saved = captor.getValue();
        assertThat(saved.isUsed()).isFalse();
        assertThat(saved.getResendCount()).isZero();
        assertThat(saved.getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void generate_shipmentNotFound_throwsIllegalArgument() {
        when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generate(SHIPMENT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(SHIPMENT_ID.toString());
    }

    @Test
    void generate_deletesExistingOtpFirst() {
        when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment()));
        when(otpRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.generate(SHIPMENT_ID);

        // deleteByShipmentId must be called before save
        verify(otpRepository).deleteByShipmentId(SHIPMENT_ID);
        verify(otpRepository).save(any());
    }

    // -------------------------------------------------------------------------
    // verify()
    // -------------------------------------------------------------------------

    @Test
    void verify_correctOtp_setsUsedTrue() {
        // BCrypt-encode a known OTP for the test record
        String plainOtp = "1234";
        PickupOtp record = otpRecord(plainOtp, Instant.now().plusSeconds(600), false, (short) 0);
        when(otpRepository.findByShipmentIdWithLock(SHIPMENT_ID)).thenReturn(Optional.of(record));
        when(otpRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.verify(SHIPMENT_ID, plainOtp);

        ArgumentCaptor<PickupOtp> captor = ArgumentCaptor.forClass(PickupOtp.class);
        verify(otpRepository).save(captor.capture());
        assertThat(captor.getValue().isUsed()).isTrue();
    }

    @Test
    void verify_wrongOtp_throwsOtpVerificationException() {
        PickupOtp record = otpRecord("1234", Instant.now().plusSeconds(600), false, (short) 0);
        when(otpRepository.findByShipmentIdWithLock(SHIPMENT_ID)).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.verify(SHIPMENT_ID, "9999"))
                .isInstanceOf(PickupOtpService.OtpVerificationException.class)
                .hasMessageContaining("incorrect");
    }

    @Test
    void verify_expiredOtp_throwsOtpVerificationException() {
        PickupOtp record = otpRecord("1234", Instant.now().minusSeconds(1), false, (short) 0);
        when(otpRepository.findByShipmentIdWithLock(SHIPMENT_ID)).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.verify(SHIPMENT_ID, "1234"))
                .isInstanceOf(PickupOtpService.OtpVerificationException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void verify_alreadyUsedOtp_throwsOtpVerificationException() {
        PickupOtp record = otpRecord("1234", Instant.now().plusSeconds(600), true, (short) 0);
        when(otpRepository.findByShipmentIdWithLock(SHIPMENT_ID)).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.verify(SHIPMENT_ID, "1234"))
                .isInstanceOf(PickupOtpService.OtpVerificationException.class)
                .hasMessageContaining("already been used");
    }

    @Test
    void verify_noRecord_throwsOtpVerificationException() {
        when(otpRepository.findByShipmentIdWithLock(SHIPMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify(SHIPMENT_ID, "1234"))
                .isInstanceOf(PickupOtpService.OtpVerificationException.class);
    }

    // -------------------------------------------------------------------------
    // resend()
    // -------------------------------------------------------------------------

    @Test
    void resend_belowLimit_generatesNewOtpAndIncrementsCount() {
        PickupOtp existing = otpRecord("1234", Instant.now().plusSeconds(600), false, (short) 1);
        when(otpRepository.findByShipmentId(SHIPMENT_ID)).thenReturn(Optional.of(existing));
        when(otpRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String newOtp = service.resend(SHIPMENT_ID);

        assertThat(newOtp).matches("\\d{4}");
        verify(otpRepository).deleteByShipmentId(SHIPMENT_ID);
        ArgumentCaptor<PickupOtp> captor = ArgumentCaptor.forClass(PickupOtp.class);
        verify(otpRepository).save(captor.capture());
        assertThat(captor.getValue().getResendCount()).isEqualTo((short) 2);
    }

    @Test
    void resend_atLimit_throwsResendLimitExceededException() {
        PickupOtp existing = otpRecord("1234", Instant.now().plusSeconds(600), false,
                (short) properties.getMaxResendCount());
        when(otpRepository.findByShipmentId(SHIPMENT_ID)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.resend(SHIPMENT_ID))
                .isInstanceOf(PickupOtpService.ResendLimitExceededException.class);
    }

    @Test
    void resend_noExistingOtp_throwsOtpVerificationException() {
        when(otpRepository.findByShipmentId(SHIPMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resend(SHIPMENT_ID))
                .isInstanceOf(PickupOtpService.OtpVerificationException.class);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Shipment shipment() {
        Shipment s = new Shipment();
        ReflectionTestUtils.setField(s, "id", SHIPMENT_ID);
        s.setState(ShipmentState.PICKUP_ASSIGNED);
        return s;
    }

    /**
     * Builds a PickupOtp with a real BCrypt hash of {@code plainOtp} so
     * BCryptPasswordEncoder.matches() works in verify() tests.
     */
    private PickupOtp otpRecord(String plainOtp, Instant expiresAt,
                                boolean used, short resendCount) {
        org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder encoder =
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4);
        PickupOtp otp = new PickupOtp();
        otp.setShipment(shipment());
        otp.setOtpHash(encoder.encode(plainOtp));
        otp.setExpiresAt(expiresAt);
        otp.setUsed(used);
        otp.setResendCount(resendCount);
        return otp;
    }
}
