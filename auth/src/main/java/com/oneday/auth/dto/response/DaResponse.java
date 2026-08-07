package com.oneday.auth.dto.response;

import com.oneday.common.domain.Shift;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A registered DA. {@code tempPassword} is populated only on the create call when the admin did not
 * supply one — hand it to the DA for first login (no email service in the pilot); it is never stored
 * or returned again.
 */
public record DaResponse(
        UUID daId,
        String name,
        String email,
        String phone,
        String cityId,
        Shift shift,
        LocalDate contractStartDate,
        LocalDate contractEndDate,
        boolean active,
        String tempPassword
) {}
