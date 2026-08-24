package com.oneday.orders.repository;

import com.oneday.orders.domain.OrderRefCounter;
import com.oneday.orders.domain.OrderRefCounterId;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

/** @see ShipmentRefCounterRepository — same SELECT FOR UPDATE + insert-if-absent pattern. */
public interface OrderRefCounterRepository extends JpaRepository<OrderRefCounter, OrderRefCounterId> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM OrderRefCounter r WHERE r.id = :id")
    Optional<OrderRefCounter> findByIdWithLock(@Param("id") OrderRefCounterId id);

    @Modifying
    @Transactional
    @Query(value = "INSERT INTO order_ref_counters (city_code, date_key, next_val) " +
                   "VALUES (:cityCode, :dateKey, 0) ON CONFLICT (city_code, date_key) DO NOTHING",
           nativeQuery = true)
    void insertIfAbsent(@Param("cityCode") String cityCode, @Param("dateKey") LocalDate dateKey);
}
