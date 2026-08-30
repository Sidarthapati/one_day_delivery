package com.oneday.orders.repository;

import com.oneday.orders.domain.DaCodLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DaCodLedgerRepository extends JpaRepository<DaCodLedgerEntry, UUID> {

    /** A DA's ledger history, newest first. */
    List<DaCodLedgerEntry> findByDaUserIdOrderByCreatedAtDesc(UUID daUserId);
}
