package com.oneday.orders.repository;

import com.oneday.orders.domain.PickupOtp;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface PickupOtpRepository extends JpaRepository<PickupOtp, UUID> {

    /**
     * Finds the active OTP record for a shipment (at most one due to unique index).
     */
    Optional<PickupOtp> findByShipmentId(UUID shipmentId);

    /**
     * Same as {@link #findByShipmentId} but acquires a pessimistic write lock
     * (SELECT FOR UPDATE). Use this in {@code verify()} to prevent concurrent
     * replay of a valid OTP — two concurrent calls both see {@code used=false}
     * without the lock; with the lock the second call blocks until the first
     * transaction commits.
     *
     * <p>Caller must be inside a {@code @Transactional} method.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM PickupOtp o WHERE o.shipment.id = :shipmentId")
    Optional<PickupOtp> findByShipmentIdWithLock(@Param("shipmentId") UUID shipmentId);

    /**
     * Deletes the OTP for a given shipment. Used on resend: delete old row, then
     * insert a fresh one. Must run inside a transaction.
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM PickupOtp o WHERE o.shipment.id = :shipmentId")
    void deleteByShipmentId(@Param("shipmentId") UUID shipmentId);
}
