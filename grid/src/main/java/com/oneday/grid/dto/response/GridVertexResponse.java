package com.oneday.grid.dto.response;

import java.util.UUID;

public record GridVertexResponse(
        UUID id,
        double lat,
        double lon
) {}
