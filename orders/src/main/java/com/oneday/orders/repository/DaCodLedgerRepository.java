package com.oneday.orders.repository;

import com.oneday.orders.domain.DaCodLedgerEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DaCodLedgerRepository extends JpaRepository<DaCodLedgerEntry, UUID> {

    /** A page of a DA's ledger history, newest first (the ledger is append-only and unbounded). */
    List<DaCodLedgerEntry> findByDaUserIdOrderByCreatedAtDesc(UUID daUserId, Pageable pageable);
}
