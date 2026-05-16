package com.oneday.grid.dto.request;

import java.util.UUID;

public record TileShareRequest(
        UUID cityId,
        UUID daId,
        UUID tileId,
        UUID requestedBy
) {}
