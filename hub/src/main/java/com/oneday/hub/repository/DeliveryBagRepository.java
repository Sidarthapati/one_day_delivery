package com.oneday.hub.repository;

import com.oneday.hub.domain.DeliveryBag;
import com.oneday.hub.domain.DeliveryBagStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeliveryBagRepository extends JpaRepository<DeliveryBag, UUID> {

    /** Lazy-create lookup for a ROUTE bag: the open bag for a (loop, date), if one exists. */
    Optional<DeliveryBag> findByVanIdAndBagDateAndStatus(UUID vanId, LocalDate bagDate, DeliveryBagStatus status);

    /** Lazy-create lookup for a DA_TERRITORY bag: the open bag for a (territory, date), if one exists. */
    Optional<DeliveryBag> findByDaTerritoryIdAndBagDateAndStatus(UUID daTerritoryId, LocalDate bagDate, DeliveryBagStatus status);

    /** Lazy-create lookup for a ZONE bag: the open bag for a (zone, date), if one exists. */
    Optional<DeliveryBag> findByZoneIdAndBagDateAndStatus(UUID zoneId, LocalDate bagDate, DeliveryBagStatus status);

    /** Operator console: all delivery bags at a hub for a day (the live dest directory). */
    List<DeliveryBag> findByHubIdAndBagDate(UUID hubId, LocalDate bagDate);

    /** Open delivery bags at a hub = occupied delivery stands (§11). */
    long countByHubIdAndStatus(UUID hubId, DeliveryBagStatus status);

    /** Parcels sitting in still-open delivery bags (in-progress sort backlog proxy, §11). */
    @Query("SELECT COALESCE(SUM(b.parcelCount), 0) FROM DeliveryBag b "
            + "WHERE b.hubId = :hubId AND b.status = com.oneday.hub.domain.DeliveryBagStatus.OPEN")
    long sumOpenParcelCount(UUID hubId);
}
