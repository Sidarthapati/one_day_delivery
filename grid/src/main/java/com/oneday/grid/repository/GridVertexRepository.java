package com.oneday.grid.repository;

import com.oneday.grid.domain.GridVertex;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GridVertexRepository extends JpaRepository<GridVertex, UUID> {

    List<GridVertex> findByGridId(UUID gridId);
}
