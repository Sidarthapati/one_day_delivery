package com.oneday.orders.repository;

import com.oneday.orders.domain.DeliveryOtp;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** Drop-side mirror of {@code PickupOtpRepository}. */
public interface DeliveryOtpRepository extends JpaRepository<DeliveryOtp, UUID> {

    /** Finds the active delivery OTP for a shipment (at most one due to the unique index). */
    Optional<DeliveryOtp> findByShipmentId(UUID shipmentId);

    /**
     * Same as {@link #findByShipmentId} but acquires a pessimistic write lock (SELECT FOR UPDATE) to
     * prevent concurrent replay of a valid OTP. Caller must be inside a {@code @Transactional} method.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM DeliveryOtp o WHERE o.shipment.id = :shipmentId")
    Optional<DeliveryOtp> findByShipmentIdWithLock(@Param("shipmentId") UUID shipmentId);

    /** Deletes the OTP for a given shipment. Used on resend: delete old row, then insert a fresh one. */
    @Transactional
    @Modifying
    @Query("DELETE FROM DeliveryOtp o WHERE o.shipment.id = :shipmentId")
    void deleteByShipmentId(@Param("shipmentId") UUID shipmentId);
}
