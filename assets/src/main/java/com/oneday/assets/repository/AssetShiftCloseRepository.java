package com.oneday.assets.repository;

import com.oneday.assets.domain.AssetShiftClose;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssetShiftCloseRepository extends JpaRepository<AssetShiftClose, UUID> {

    /** The closes recorded for a city on a date (both shifts), newest first. */
    List<AssetShiftClose> findByCityIdAndCloseDateOrderByClosedAtDesc(UUID cityId, LocalDate closeDate);

    /** The most recent close for a city — the incoming shift's opening state. */
    Optional<AssetShiftClose> findFirstByCityIdOrderByClosedAtDesc(UUID cityId);
}
