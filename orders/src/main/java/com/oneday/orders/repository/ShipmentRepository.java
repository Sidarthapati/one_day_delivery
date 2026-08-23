package com.oneday.orders.repository;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.Shipment;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShipmentRepository extends JpaRepository<Shipment, UUID> {

    /**
     * Acquires a pessimistic write lock (SELECT FOR UPDATE) on the shipment row.
     * Must be called from within a {@code @Transactional} method.
     * Used by the state machine to prevent concurrent transitions on the same shipment.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Shipment s WHERE s.id = :id")
    Optional<Shipment> findByIdWithLock(@Param("id") UUID id);

    /**
     * Denormalize the latest scan (dwell/ageing primitive) atomically — the newer-only guard is in the
     * WHERE clause, so concurrent scan consumers can't lose a newer timestamp (no read-check-write race).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Shipment s SET s.lastScanAt = :scannedAt, s.lastScanType = :scanType "
            + "WHERE s.id = :id AND (s.lastScanAt IS NULL OR s.lastScanAt < :scannedAt)")
    int updateLastScanIfNewer(@Param("id") UUID id,
                              @Param("scannedAt") Instant scannedAt,
                              @Param("scanType") String scanType);

    Optional<Shipment> findByShipmentRef(String shipmentRef);

    Optional<Shipment> findByTrackToken(String trackToken);

    /** Unbounded list — use only when result set is known to be small (e.g. admin tooling). */
    List<Shipment> findByState(ShipmentState state);

    /** Paginated variant — preferred for service-layer and API use; avoids full-table loads. */
    Page<Shipment> findByState(ShipmentState state, Pageable pageable);

    /** Unbounded list — use only when result set is known to be small (e.g. admin tooling). */
    List<Shipment> findByStateAndCityId(ShipmentState state, String cityId);

    /** Paginated variant — preferred for service-layer and API use; avoids full-table loads. */
    Page<Shipment> findByStateAndCityId(ShipmentState state, String cityId, Pageable pageable);

    boolean existsByIdempotencyKey(String idempotencyKey);

    // Used to verify that the read transformer on customer_type enables WHERE-clause filtering.
    // Also needed by reporting and B2B billing queries in the service layer.
    List<Shipment> findByCustomerType(CustomerType customerType);

    // Used by M9 to find all shipments assigned to a specific flight.
    List<Shipment> findByAssignedFlightId(UUID assignedFlightId);

    // Customer "my shipments" view: every shipment a given M1 user booked, newest first.
    Page<Shipment> findByBookedByUserId(UUID bookedByUserId, Pageable pageable);

    // Admin orders-DB view, station-manager scope: every shipment whose origin OR destination
    // is the manager's city (custody model — a city role sees both legs touching its city).
    @Query("SELECT s FROM Shipment s WHERE s.originCity = :city OR s.destCity = :city")
    Page<Shipment> findByCityInvolved(@Param("city") String city, Pageable pageable);

    @Query("SELECT s FROM Shipment s WHERE (s.originCity = :city OR s.destCity = :city) AND s.state = :state")
    Page<Shipment> findByCityInvolvedAndState(@Param("city") String city,
                                              @Param("state") ShipmentState state,
                                              Pageable pageable);

    // Ops monitoring: one grouped count instead of N per-state list calls. Backed by
    // idx_shipments_state / (origin_city|dest_city, state) — see V4_35.
    @Query("SELECT s.state AS state, COUNT(s) AS count FROM Shipment s GROUP BY s.state")
    List<StateCount> countByState();

    // City-scoped variant — same origin-OR-dest rule as findByCityInvolved (station-manager scope).
    @Query("SELECT s.state AS state, COUNT(s) AS count FROM Shipment s "
            + "WHERE s.originCity = :city OR s.destCity = :city GROUP BY s.state")
    List<StateCount> countByStateForCity(@Param("city") String city);

    /**
     * Ageing rollup: live (non-terminal) shipments grouped by state and a dwell band. Dwell is
     * {@code now() − COALESCE(last_scan_at, created_at)} (a never-scanned parcel ages from booking).
     * Bands: 0 = {@code < t1}s, 1 = {@code < t2}s, 2 = {@code < t3}s, 3 = older. {@code city} null → all.
     * Native (Postgres). Uses {@code cast(state as text)} not {@code state::text} — Hibernate mis-parses
     * the {@code ::} cast as a {@code :}-named parameter in native queries (verified failing at runtime).
     */
    @Query(value = """
            SELECT cast(s.state as text) AS state,
                   CASE
                       WHEN EXTRACT(EPOCH FROM now() - COALESCE(s.last_scan_at, s.created_at)) < :t1 THEN 0
                       WHEN EXTRACT(EPOCH FROM now() - COALESCE(s.last_scan_at, s.created_at)) < :t2 THEN 1
                       WHEN EXTRACT(EPOCH FROM now() - COALESCE(s.last_scan_at, s.created_at)) < :t3 THEN 2
                       ELSE 3
                   END AS band,
                   COUNT(*) AS cnt
            FROM shipments s
            WHERE cast(s.state as text) NOT IN ('DROPPED', 'HUB_COLLECTED', 'RTO_COMPLETED', 'CANCELLED')
              AND (:city IS NULL OR s.origin_city = :city OR s.dest_city = :city)
            GROUP BY s.state, band
            """, nativeQuery = true)
    List<AgeingBandCount> ageingByStateAndBand(@Param("city") String city,
                                               @Param("t1") long t1Seconds,
                                               @Param("t2") long t2Seconds,
                                               @Param("t3") long t3Seconds);
}
