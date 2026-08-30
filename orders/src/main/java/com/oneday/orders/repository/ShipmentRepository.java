package com.oneday.orders.repository;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.PickupType;
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
import java.util.Collection;
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

    /** The return child (<ref>_R) spawned for an original shipment, if one exists (idempotency guard). */
    Optional<Shipment> findByReturnOfShipmentId(UUID originalShipmentId);

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

    // Merchant self-service export: every shipment booked against one B2B account (any of the
    // account's users), newest first. Ownership is enforced by the caller before this runs.
    Page<Shipment> findByB2bAccountId(UUID b2bAccountId, Pageable pageable);

    // Pickup-slot capacity: how many active (non-cancelled) DA-pickup reservations already hold a
    // given city's slot (identified by its absolute start instant). Only DA_PICKUP shipments consume
    // a pickup slot — a SELF_DROP parcel needs no DA. Backs the per-slot cap at booking time.
    int countByOriginCityAndScheduledPickupStartAndPickupTypeAndCancelledAtIsNull(
            String originCity, Instant scheduledPickupStart, PickupType pickupType);

    // Pickup-slot availability: booked count per slot start across a city + time window, in ONE grouped
    // query (instead of one count per candidate slot). Backs the "hide full slots" picker.
    @Query("SELECT s.scheduledPickupStart AS start, COUNT(s) AS count FROM Shipment s "
            + "WHERE s.originCity = :city AND s.pickupType = :pickupType AND s.cancelledAt IS NULL "
            + "AND s.scheduledPickupStart >= :from AND s.scheduledPickupStart < :to "
            + "GROUP BY s.scheduledPickupStart")
    List<SlotBookingCount> countBookedSlotsInRange(@Param("city") String city,
                                                   @Param("pickupType") PickupType pickupType,
                                                   @Param("from") Instant from, @Param("to") Instant to);

    // Order → N Shipments: the child shipments of one order (booking order preserved).
    List<Shipment> findByOrderIdOrderByCreatedAtAsc(UUID orderId);

    // Bulk rollup input: (orderId, state) pairs for a set of orders, to reduce each order's status
    // in one query instead of N per-order lookups. See OrderStatusReducer.
    @Query("SELECT s.orderId AS orderId, s.state AS state FROM Shipment s WHERE s.orderId IN :orderIds")
    List<OrderChildState> findChildStatesByOrderIds(@Param("orderIds") Collection<UUID> orderIds);

    /** Projection for the bulk order-status rollup. */
    interface OrderChildState {
        UUID getOrderId();
        ShipmentState getState();
    }

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

    // ── Merchant analytics (per B2B account, windowed by booking time) ─────────────────────────
    // :since is always bound (the service passes Instant.EPOCH for all-time) — a nullable bind in a
    // "? IS NULL" branch left Postgres unable to infer the parameter type. Same grouped-count shape
    // as countByState, scoped to one account.

    @Query("SELECT s.state AS state, COUNT(s) AS count FROM Shipment s "
            + "WHERE s.b2bAccountId = :accountId AND s.createdAt >= :since "
            + "GROUP BY s.state")
    List<StateCount> countByStateForAccount(@Param("accountId") UUID accountId, @Param("since") Instant since);

    // Money rollup: shipping GMV + COD value handled, in paise. COALESCE keeps it 0 (never null) for
    // an account with no shipments in the window.
    @Query("SELECT COALESCE(SUM(s.totalPricePaise), 0) AS gmvPaise, "
            + "COALESCE(SUM(s.codAmountPaise), 0) AS codPaise "
            + "FROM Shipment s WHERE s.b2bAccountId = :accountId "
            + "AND s.createdAt >= :since")
    AccountTotals sumTotalsForAccount(@Param("accountId") UUID accountId, @Param("since") Instant since);

    // Destination-city split (only 5 serviceable cities, so a tiny result set), busiest first.
    @Query("SELECT s.destCity AS city, COUNT(s) AS count FROM Shipment s "
            + "WHERE s.b2bAccountId = :accountId AND s.createdAt >= :since "
            + "GROUP BY s.destCity ORDER BY COUNT(s) DESC")
    List<CityCount> destinationSplitForAccount(@Param("accountId") UUID accountId, @Param("since") Instant since);

    // Category split — one row per category_id (null = untagged), busiest first. Names resolved in the service.
    @Query("SELECT s.categoryId AS categoryId, COUNT(s) AS count FROM Shipment s "
            + "WHERE s.b2bAccountId = :accountId AND s.createdAt >= :since "
            + "GROUP BY s.categoryId ORDER BY COUNT(s) DESC")
    List<CategoryCount> categorySplitForAccount(@Param("accountId") UUID accountId, @Param("since") Instant since);

    // On-time delivery: joins each parcel's actual delivered time (the history row's occurred_at for a
    // delivered terminal state) against its promised ETA. Theta-join because shipment↔history is a bare
    // UUID reference, not a mapped association. Only parcels with a promised ETA are rated.
    @Query("SELECT COUNT(h) AS delivered, "
            + "COALESCE(SUM(CASE WHEN h.occurredAt <= s.etaPromised THEN 1 ELSE 0 END), 0) AS onTime "
            + "FROM ShipmentStateHistory h, Shipment s "
            + "WHERE h.shipmentId = s.id AND h.toState IN :deliveredStates "
            + "AND s.b2bAccountId = :accountId AND s.etaPromised IS NOT NULL "
            + "AND s.createdAt >= :since")
    OnTimeStat onTimeForAccount(@Param("accountId") UUID accountId,
                                @Param("deliveredStates") Collection<ShipmentState> deliveredStates,
                                @Param("since") Instant since);

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
