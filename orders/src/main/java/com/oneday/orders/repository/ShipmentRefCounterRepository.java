package com.oneday.orders.repository;

import com.oneday.orders.domain.ShipmentRefCounter;
import com.oneday.orders.domain.ShipmentRefCounterId;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

public interface ShipmentRefCounterRepository extends JpaRepository<ShipmentRefCounter, ShipmentRefCounterId> {

    /**
     * Acquires a pessimistic write lock (SELECT FOR UPDATE) on the counter row.
     * The caller must be inside a {@code @Transactional} method and should increment
     * {@link ShipmentRefCounter#setNextVal} in Java after calling this method; the ORM
     * will flush the update atomically when the transaction commits.
     *
     * <p>Using a DB-level lock instead of a bulk UPDATE means the new value is available
     * in the same Java call without a second round-trip, and no concurrent transaction can
     * interleave between the read and the write.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ShipmentRefCounter r WHERE r.id = :id")
    Optional<ShipmentRefCounter> findByIdWithLock(@Param("id") ShipmentRefCounterId id);

    /**
     * Inserts a counter row with {@code next_val = 0} if one does not already exist
     * for the given {@code (city_code, date_key)} pair. A concurrent INSERT is handled
     * by {@code ON CONFLICT DO NOTHING} — exactly one row wins and the others silently
     * skip. Call this before {@link #findByIdWithLock} so that the SELECT FOR UPDATE
     * always finds an existing row (SELECT FOR UPDATE cannot lock a non-existent row).
     *
     * <p>Caller must be inside a {@code @Transactional} method.</p>
     */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO shipment_ref_counters (city_code, date_key, next_val) " +
                   "VALUES (:cityCode, :dateKey, 0) ON CONFLICT (city_code, date_key) DO NOTHING",
           nativeQuery = true)
    void insertIfAbsent(@Param("cityCode") String cityCode, @Param("dateKey") LocalDate dateKey);
}
