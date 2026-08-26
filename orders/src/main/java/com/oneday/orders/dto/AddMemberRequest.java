package com.oneday.orders.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Invite an existing Godspeed business user to the caller's account (as a MEMBER). */
public record AddMemberRequest(@NotBlank @Email String email) {
}
