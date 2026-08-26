package com.oneday.exceptions.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A reply posted into a support ticket's thread, by either the raiser or an ops agent. */
public record PostMessageRequest(
        @NotBlank @Size(max = 4000) String body) {
}
