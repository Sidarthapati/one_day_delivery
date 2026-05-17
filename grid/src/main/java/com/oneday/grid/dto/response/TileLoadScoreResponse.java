package com.oneday.grid.dto.response;

import java.time.LocalDate;
import java.util.UUID;

public record TileLoadScoreResponse(
        UUID tileId,
        LocalDate date,
        int unservedOrders,
        double adjustedLoadScore,
        String severity
) {}
