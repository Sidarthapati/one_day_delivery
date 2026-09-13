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
     * A page of a DA's ledger history, newest first, optionally bounded by a created-at range (G3).
     * A null bound is open on that side, so the plain paged read passes null/null (unbounded).
     */
    @Query("select e from DaCodLedgerEntry e where e.daUserId = :daUserId "
            + "and (:from is null or e.createdAt >= :from) "
            + "and (:to is null or e.createdAt <= :to) "
            + "order by e.createdAt desc")
    List<DaCodLedgerEntry> findByDaInRange(@Param("daUserId") UUID daUserId,
                                           @Param("from") Instant from,
                                           @Param("to") Instant to,
                                           Pageable pageable);
}
