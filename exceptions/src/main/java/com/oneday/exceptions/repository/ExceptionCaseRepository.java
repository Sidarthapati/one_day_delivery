package com.oneday.exceptions.repository;

import com.oneday.exceptions.domain.ExceptionCase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExceptionCaseRepository extends JpaRepository<ExceptionCase, UUID> {

    /** The one live (unresolved) case for a shipment, if any — the idempotency key for open/bump. */
    Optional<ExceptionCase> findFirstByShipmentIdAndResolvedAtIsNull(UUID shipmentId);

    /**
     * The problem-solve queue: live cases, city-scoped (origin or dest matches), optional type filter,
     * freshest first. {@code city == null} = admin (all cities).
     */
    @Query("""
            select c from ExceptionCase c
            where c.resolvedAt is null
              and (:city is null or c.originCity = :city or c.destCity = :city)
              and (:type is null or c.type = :type)
            order by c.openedAt desc
            """)
    Page<ExceptionCase> queue(@Param("city") String city,
                              @Param("type") com.oneday.exceptions.domain.ExceptionType type,
                              Pageable pageable);

    /** Disposition rollup counts over the live set for a city scope. */
    @Query("""
            select c.disposition, count(c) from ExceptionCase c
            where c.resolvedAt is null
              and (:city is null or c.originCity = :city or c.destCity = :city)
            group by c.disposition
            """)
    List<Object[]> countOpenByDisposition(@Param("city") String city);

    /** Convenience count for a single disposition — used by the summary card totals. */
    long countByResolvedAtIsNull();
}
