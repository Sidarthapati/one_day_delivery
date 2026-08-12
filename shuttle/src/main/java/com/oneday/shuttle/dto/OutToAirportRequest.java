package com.oneday.shuttle.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

/** One trip can carry several sealed bags — batch them so a single run serves multiple flights. */
public record OutToAirportRequest(@NotEmpty List<UUID> bagIds) {
}
