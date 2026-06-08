package com.oneday.orders.repository;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.Shipment;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    Optional<Shipment> findByShipmentRef(String shipmentRef);

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
}
