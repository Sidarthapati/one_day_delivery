package com.oneday.exceptions.dto;

/** Disposition rollups over the live queue — the header cards on the station Exceptions page. */
public record ExceptionSummaryResponse(
        long open,
        long reattemptable,
        long undeliverable,
        long returned) {
}
