package com.oneday.orders.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code POST /internal/v1/shipments/{ref}/pickup-otp/verify}.
 */
public class OtpVerifyRequest {

    /** The 4-digit OTP entered by the DA. */
    @NotBlank(message = "otp is required")
    @Pattern(regexp = "\\d{4}", message = "otp must be exactly 4 digits")
    private String otp;

    public String getOtp() { return otp; }
    public void setOtp(String otp) { this.otp = otp; }
}
