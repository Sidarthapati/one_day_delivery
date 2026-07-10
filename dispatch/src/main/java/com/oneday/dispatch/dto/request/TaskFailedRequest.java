package com.oneday.dispatch.dto.request;

/** Reason a pickup/delivery task failed (free text; optional). */
public record TaskFailedRequest(String reason) {
}
