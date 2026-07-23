package com.oneday.sla.dto;

/** Body for acknowledging / acting on an escalation. {@code notes} is optional. */
public record SlaActionRequest(String notes) {
}
