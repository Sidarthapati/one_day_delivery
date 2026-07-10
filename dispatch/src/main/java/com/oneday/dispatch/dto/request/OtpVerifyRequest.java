package com.oneday.dispatch.dto.request;

import jakarta.validation.constraints.NotBlank;

/** The OTP the sender showed the DA at pickup. */
public record OtpVerifyRequest(@NotBlank String otp) {
}
