package com.oneday.airline.dto;

import jakarta.validation.constraints.NotBlank;

/** The real AWB number Bhagwati hands us for a flight (admin entry / WhatsApp form). */
public record AwbIntakeRequest(@NotBlank String awbNo) {
}
