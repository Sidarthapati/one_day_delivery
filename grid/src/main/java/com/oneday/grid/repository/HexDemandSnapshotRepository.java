package com.oneday.grid.repository;

import com.oneday.grid.domain.HexDemandSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HexDemandSnapshotRepository extends JpaRepository<HexDemandSnapshot, UUID> {

    Optional<HexDemandSnapshot> findByHexIdAndSnapshotDate(UUID hexId, LocalDate snapshotDate);

    List<HexDemandSnapshot> findBySnapshotDate(LocalDate snapshotDate);
}
