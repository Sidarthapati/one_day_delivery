package com.oneday.exceptions.dto;

import com.oneday.exceptions.domain.ExceptionAction;

import java.time.Instant;

/** One entry in a case's action history. */
public record ExceptionActionView(String action, String actedBy, String actedByRole, String notes, Instant at) {

    public static ExceptionActionView from(ExceptionAction a) {
        return new ExceptionActionView(a.getAction(), a.getActedBy(), a.getActedByRole(), a.getNotes(), a.getCreatedAt());
    }
}
