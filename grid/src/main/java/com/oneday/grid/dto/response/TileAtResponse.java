package com.oneday.grid.dto.response;

import java.util.UUID;

public record TileAtResponse(
        UUID hexId,
        String h3Index,
        boolean active
) {}
