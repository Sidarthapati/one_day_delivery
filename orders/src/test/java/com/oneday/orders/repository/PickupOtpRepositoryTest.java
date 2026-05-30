package com.oneday.orders.repository;

import com.oneday.orders.domain.PickupOtp;
import com.oneday.orders.domain.Shipment;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PickupOtpRepositoryTest extends AbstractRepositoryTest {

    @Autowired private PickupOtpRepository otpRepository;
    @Autowired private ShipmentRepository shipmentRepository;

    // -------------------------------------------------------------------------
    // findByShipmentId
    // -------------------------------------------------------------------------

    @Test
    void findByShipmentId_existingOtp_returnsIt() {
        Shipment shipment = shipmentRepository.save(TestFixtures.shipment("1DD-BLR-20260530-00001", "city-blr"));
        otpRepository.save(pickupOtp(shipment, "hashed-otp-1", false, (short) 0));

        Optional<PickupOtp> found = otpRepository.findByShipmentId(shipment.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getOtpHash()).isEqualTo("hashed-otp-1");
        assertThat(found.get().isUsed()).isFalse();
    }

    @Test
    void findByShipmentId_unknownShipment_returnsEmpty() {
        Optional<PickupOtp> found = otpRepository.findByShipmentId(java.util.UUID.randomUUID());
        assertThat(found).isEmpty();
    }

    // -------------------------------------------------------------------------
    // deleteByShipmentId
    // -------------------------------------------------------------------------

    @Test
    void deleteByShipmentId_removesRow() {
        Shipment shipment = shipmentRepository.save(TestFixtures.shipment("1DD-BLR-20260530-00002", "city-blr"));
        otpRepository.save(pickupOtp(shipment, "hashed-otp-2", false, (short) 0));

        otpRepository.deleteByShipmentId(shipment.getId());

        assertThat(otpRepository.findByShipmentId(shipment.getId())).isEmpty();
    }

    @Test
    void deleteByShipmentId_unknownShipment_noOp() {
        // Should not throw
        otpRepository.deleteByShipmentId(java.util.UUID.randomUUID());
    }

    // -------------------------------------------------------------------------
    // Unique constraint: one OTP per shipment
    // -------------------------------------------------------------------------

    @Test
    void save_duplicateOtpForSameShipment_throwsConstraintViolation() {
        Shipment shipment = shipmentRepository.save(TestFixtures.shipment("1DD-BLR-20260530-00003", "city-blr"));
        otpRepository.save(pickupOtp(shipment, "hash-a", false, (short) 0));

        assertThatThrownBy(() -> {
            otpRepository.saveAndFlush(pickupOtp(shipment, "hash-b", false, (short) 0));
        }).isInstanceOf(Exception.class); // DataIntegrityViolationException or PersistenceException
    }

    // -------------------------------------------------------------------------
    // used flag can be updated
    // -------------------------------------------------------------------------

    @Test
    void save_updatingUsedFlag_persists() {
        Shipment shipment = shipmentRepository.save(TestFixtures.shipment("1DD-BLR-20260530-00004", "city-blr"));
        PickupOtp otp = otpRepository.save(pickupOtp(shipment, "hash-c", false, (short) 0));

        otp.setUsed(true);
        PickupOtp updated = otpRepository.save(otp);

        assertThat(updated.isUsed()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private PickupOtp pickupOtp(Shipment shipment, String otpHash, boolean used, short resendCount) {
        PickupOtp otp = new PickupOtp();
        otp.setShipment(shipment);
        otp.setOtpHash(otpHash);
        otp.setExpiresAt(Instant.now().plusSeconds(600));
        otp.setUsed(used);
        otp.setResendCount(resendCount);
        return otp;
    }
}
