package com.oneday.orders.repository;

import com.oneday.orders.domain.DaCodLedgerEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DaCodLedgerRepository extends JpaRepository<DaCodLedgerEntry, UUID> {

    /**
     * A page of a DA's ledger history, newest first, bounded by a created-at range (G3). Callers pass
     * wide sentinels (EPOCH / far-future) for an open side rather than null — Postgres can't infer the
     * type of a null bind param that only ever appears in an `IS NULL` test, so both bounds stay non-null.
     */
    @Query("select e from DaCodLedgerEntry e where e.daUserId = :daUserId "
            + "and e.createdAt >= :from and e.createdAt <= :to "
            + "order by e.createdAt desc")
    List<DaCodLedgerEntry> findByDaInRange(@Param("daUserId") UUID daUserId,
                                           @Param("from") Instant from,
                                           @Param("to") Instant to,
                                           Pageable pageable);
}
