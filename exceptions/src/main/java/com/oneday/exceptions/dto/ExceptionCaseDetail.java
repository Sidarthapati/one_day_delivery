package com.oneday.exceptions.dto;

import com.oneday.orders.dto.JourneyStep;

import java.util.List;

/**
 * A case plus the full picture the manager needs to act: the M11 action log, the shipment's whole
 * internal journey (every step, timestamped, with actor + source), and the people to call at the
 * failed stage — the current handler (DA) and the receiving customer.
 */
public record ExceptionCaseDetail(
        ExceptionCaseSummary caseSummary,
        List<ExceptionActionView> actions,
        List<JourneyStep> journey,
        Contact handler,
        Contact receiver) {

    /** A callable person: name + phone (+ role for the handler). */
    public record Contact(String name, String phone, String role) {}
}
