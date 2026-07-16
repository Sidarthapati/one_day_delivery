package com.oneday.sla.repository;

import com.oneday.common.domain.enums.SlaState;
import com.oneday.sla.domain.SlaShipment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SlaShipmentRepository extends JpaRepository<SlaShipment, UUID> {

    Optional<SlaShipment> findByShipmentId(UUID shipmentId);

    Optional<SlaShipment> findByShipmentRef(String shipmentRef);

    /** Every still-open SLA — the sweeper's working set. */
    List<SlaShipment> findByClosedAtIsNull();

    /**
     * Control-tower page: open shipments, optionally filtered to a colour and/or a city (matched on
     * either origin or destination — a station manager sees a parcel touching their city either way).
     */
    @Query("""
            select s from SlaShipment s
            where s.closedAt is null
              and (:state is null or s.overallState = :state)
              and (:city is null or s.originCity = :city or s.destCity = :city)
            order by s.updatedAt desc
            """)
    Page<SlaShipment> controlTower(@Param("state") SlaState state,
                                   @Param("city") String city,
                                   Pageable pageable);

    /** Open shipments at or past a given colour — the red queue. */
    @Query("""
            select s from SlaShipment s
            where s.closedAt is null
              and s.overallState in :states
              and (:city is null or s.originCity = :city or s.destCity = :city)
            order by s.projectedFinishAt asc nulls last
            """)
    List<SlaShipment> openByStates(@Param("states") List<SlaState> states, @Param("city") String city);

    @Query("""
            select count(s) from SlaShipment s
            where s.closedAt between :from and :to
              and (:city is null or s.originCity = :city or s.destCity = :city)
            """)
    long countClosedBetween(@Param("from") Instant from, @Param("to") Instant to, @Param("city") String city);

    @Query("""
            select count(s) from SlaShipment s
            where s.closedAt between :from and :to
              and s.breached = true
              and (:city is null or s.originCity = :city or s.destCity = :city)
            """)
    long countBreachedBetween(@Param("from") Instant from, @Param("to") Instant to, @Param("city") String city);
}
