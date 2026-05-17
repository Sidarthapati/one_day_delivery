package com.oneday.grid.dto.response;

import java.util.UUID;

public record GridVertexResponse(
        UUID id,
        int rowIdx,
        int colIdx,
        double lat,
        double lon
) {}
