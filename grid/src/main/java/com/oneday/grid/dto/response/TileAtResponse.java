package com.oneday.grid.dto.response;

import java.util.UUID;

public record TileAtResponse(
        UUID tileId,
        int rowIdx,
        int colIdx,
        boolean active
) {}
