package com.oneday.grid.repository;

import com.oneday.grid.domain.Tile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TileRepository extends JpaRepository<Tile, UUID> {

    List<Tile> findByGridId(UUID gridId);

    // Field is named 'active' in the entity; JPA derives 'ActiveTrue' correctly.
    List<Tile> findByGridIdAndActiveTrue(UUID gridId);

    Optional<Tile> findByGridIdAndRowIdxAndColIdx(UUID gridId, int rowIdx, int colIdx);
}
