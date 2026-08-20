package com.oneday.orders.repository;

import com.oneday.orders.domain.CodRemittance;
import com.oneday.orders.domain.CodRemittanceState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CodRemittanceRepository extends JpaRepository<CodRemittance, UUID> {

    /** Pessimistic row lock so a PENDING→PAID transition can't be applied twice concurrently. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM CodRemittance r WHERE r.id = :id")
    Optional<CodRemittance> findByIdForUpdate(@Param("id") UUID id);

    List<CodRemittance> findByB2bAccountIdOrderByCreatedAtDesc(UUID b2bAccountId);

    List<CodRemittance> findByStateOrderByCreatedAtDesc(CodRemittanceState state);

    List<CodRemittance> findAllByOrderByCreatedAtDesc();

    /** Next value of the remittance serial sequence (defined in V4_25). */
    @Query(value = "SELECT nextval('cod_remittance_seq')", nativeQuery = true)
    long nextRemittanceSequence();
}
