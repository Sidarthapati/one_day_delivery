package com.oneday.orders.repository;

import com.oneday.orders.domain.ParcelOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ParcelOrderRepository extends JpaRepository<ParcelOrder, UUID> {

    Optional<ParcelOrder> findByOrderRef(String orderRef);

    /** Customer "my orders" — every order a given M1 user placed, newest first. */
    Page<ParcelOrder> findByBookedByUserId(UUID bookedByUserId, Pageable pageable);

    /** Admin console — all orders, newest first (ADMIN scope). */
    Page<ParcelOrder> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Admin console — orders for one origin city (station-manager scope), newest first. */
    Page<ParcelOrder> findByCityIdOrderByCreatedAtDesc(String cityId, Pageable pageable);

    /**
     * Advance the denormalized rollup atomically as each shipment is booked. The increment is done
     * in the WHERE-less UPDATE (not read-modify-write) so N concurrent cart-item transactions each
     * booking against the same parent order can't lose a count.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ParcelOrder o SET o.parcelCount = o.parcelCount + 1, "
            + "o.totalPricePaise = o.totalPricePaise + :amountPaise WHERE o.id = :id")
    int addShipment(@Param("id") UUID id, @Param("amountPaise") long amountPaise);
}
