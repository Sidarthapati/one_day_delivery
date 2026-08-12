package com.oneday.shuttle.repository;

import com.oneday.shuttle.domain.ShuttleDirection;
import com.oneday.shuttle.domain.ShuttleLeg;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ShuttleLegRepository extends JpaRepository<ShuttleLeg, UUID> {

    /** The carrying agent for a parcel = its most recent leg (used by the live-position adapter). */
    Optional<ShuttleLeg> findFirstByParcelIdOrderByCreatedAtDesc(UUID parcelId);

    /** Inbound "already collected" filter: an AWB drops out of both agents' queues once a leg exists. */
    boolean existsByAwbIdAndDirection(UUID awbId, ShuttleDirection direction);
}
