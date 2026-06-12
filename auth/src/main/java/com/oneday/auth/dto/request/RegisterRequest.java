package com.oneday.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8) String password,
        @NotBlank String name,
        // E.164 (+91XXXXXXXXXX) — same format M4 requires for sender_phone, since this
        // number is pre-filled and locked as the sender contact on C2C bookings.
        @NotBlank @Size(max = 15) @Pattern(regexp = "\\+91[0-9]{10}",
                message = "must be E.164 format (+91XXXXXXXXXX)") String phone
) {}
