package com.oneday.grid.repository;

import com.oneday.grid.domain.Hex;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HexRepository extends JpaRepository<Hex, UUID> {

    List<Hex> findByH3GridId(UUID h3GridId);

    List<Hex> findByH3GridIdAndActiveTrue(UUID h3GridId);

    Optional<Hex> findByH3GridIdAndH3Index(UUID h3GridId, long h3Index);

    // All city grids share one H3 resolution and H3 cells are globally unique, so an h3Index
    // identifies at most one hex across every grid — lets serviceableAt resolve a point in a
    // single query instead of scanning every city's grid.
    Optional<Hex> findByH3Index(long h3Index);
}
