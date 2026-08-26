package com.oneday.exceptions.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One attendance alert for the station console. */
public record AttendanceAlertResponse(
        UUID id,
        UUID daId,
        String daName,
        UUID cityId,
        String cityCode,
        LocalDate attendanceDate,
        String shift,
        String status,
        String resolution,
        Instant createdAt) {
}
