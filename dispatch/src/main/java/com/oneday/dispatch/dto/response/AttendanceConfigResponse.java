package com.oneday.dispatch.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * The global attendance config as returned to the admin console. Serialised snake_case
 * ({@code auto_present_enabled}, {@code updated_at}, {@code updated_by}).
 */
public record AttendanceConfigResponse(boolean autoPresentEnabled, Instant updatedAt, UUID updatedBy) {
}
