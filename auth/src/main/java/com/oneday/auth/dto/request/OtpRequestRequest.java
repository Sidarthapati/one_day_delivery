package com.oneday.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Ask for an OTP to be sent to a phone number (E.164, +91XXXXXXXXXX). */
public record OtpRequestRequest(
        @NotBlank @Size(max = 15) @Pattern(regexp = "\\+91[0-9]{10}",
                message = "must be E.164 format (+91XXXXXXXXXX)") String phone
) {}
