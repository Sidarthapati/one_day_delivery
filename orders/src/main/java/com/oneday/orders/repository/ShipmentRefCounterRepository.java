package com.oneday.orders.repository;

import com.oneday.orders.domain.ShipmentRefCounter;
import com.oneday.orders.domain.ShipmentRefCounterId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShipmentRefCounterRepository extends JpaRepository<ShipmentRefCounter, ShipmentRefCounterId> {

    @Modifying(clearAutomatically = true)
    @Query("UPDATE ShipmentRefCounter r SET r.nextVal = r.nextVal + 1 WHERE r.id = :id")
    int increment(@Param("id") ShipmentRefCounterId id);
}
