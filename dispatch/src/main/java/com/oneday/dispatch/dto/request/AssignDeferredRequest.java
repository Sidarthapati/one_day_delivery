package com.oneday.dispatch.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Body for the station manager's manual "Assign" action on a deferred pickup/delivery. */
public record AssignDeferredRequest(@NotNull UUID daId) {
}
