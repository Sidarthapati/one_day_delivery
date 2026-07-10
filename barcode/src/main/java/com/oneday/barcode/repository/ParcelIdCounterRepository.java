package com.oneday.barcode.repository;

import com.oneday.barcode.domain.ParcelIdCounter;
import com.oneday.barcode.domain.ParcelIdCounterId;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Mirrors {@code orders.ShipmentRefCounterRepository}: {@link #insertIfAbsent} materialises the row
 * ({@code ON CONFLICT DO NOTHING} for concurrent first-of-day inserts), then {@link #findByIdWithLock}
 * takes a SELECT FOR UPDATE so the caller reads-and-increments {@code next_seq} atomically in one
 * round-trip. Caller must be {@code @Transactional} (PR2's parcel-id generator).
 */
public interface ParcelIdCounterRepository extends JpaRepository<ParcelIdCounter, ParcelIdCounterId> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ParcelIdCounter c WHERE c.id = :id")
    Optional<ParcelIdCounter> findByIdWithLock(@Param("id") ParcelIdCounterId id);

    @Modifying
    @Transactional
    @Query(value = "INSERT INTO parcel_id_counter (hub_iata, day, next_seq) " +
                   "VALUES (:hubIata, :day, 1) ON CONFLICT (hub_iata, day) DO NOTHING",
           nativeQuery = true)
    void insertIfAbsent(@Param("hubIata") String hubIata, @Param("day") LocalDate day);
}
