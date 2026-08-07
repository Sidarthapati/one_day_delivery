package com.oneday.grid.dto.request;

import com.oneday.common.domain.Shift;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ReplanRequest(
        List<UUID> daIds,
        LocalDate date,
        Shift shift
) {}
