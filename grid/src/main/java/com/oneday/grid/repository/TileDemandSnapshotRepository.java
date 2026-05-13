package com.oneday.grid.repository;

import com.oneday.grid.domain.TileDemandSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TileDemandSnapshotRepository extends JpaRepository<TileDemandSnapshot, UUID> {

    Optional<TileDemandSnapshot> findByTileIdAndSnapshotDate(UUID tileId, LocalDate snapshotDate);

    // Used by NightlyReplanJob to load all tile snapshots for a city on a given date.
    List<TileDemandSnapshot> findBySnapshotDate(LocalDate snapshotDate);
}
