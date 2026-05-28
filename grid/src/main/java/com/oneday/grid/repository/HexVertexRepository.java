package com.oneday.grid.repository;

import com.oneday.grid.domain.HexVertex;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface HexVertexRepository extends JpaRepository<HexVertex, UUID> {

    List<HexVertex> findByH3GridId(UUID h3GridId);
}
