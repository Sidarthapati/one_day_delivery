package com.oneday.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Verify the code the user received on their phone, exchanging it for a session token. */
public record OtpVerifyRequest(
        @NotBlank @Size(max = 15) @Pattern(regexp = "\\+91[0-9]{10}",
                message = "must be E.164 format (+91XXXXXXXXXX)") String phone,
        @NotBlank @Pattern(regexp = "[0-9]{4,8}", message = "must be a numeric code") String otp
) {}
