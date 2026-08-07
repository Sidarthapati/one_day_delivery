package com.oneday.orders.repository;

import com.oneday.orders.domain.CodRemittance;
import com.oneday.orders.domain.CodRemittanceState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface CodRemittanceRepository extends JpaRepository<CodRemittance, UUID> {

    List<CodRemittance> findByB2bAccountIdOrderByCreatedAtDesc(UUID b2bAccountId);

    List<CodRemittance> findByStateOrderByCreatedAtDesc(CodRemittanceState state);

    List<CodRemittance> findAllByOrderByCreatedAtDesc();

    /** Next value of the remittance serial sequence (defined in V4_25). */
    @Query(value = "SELECT nextval('cod_remittance_seq')", nativeQuery = true)
    long nextRemittanceSequence();
}
