package com.oneday.routing.service.model;

import java.util.List;

// Aggregated outcome of a reconciliation sweep over many parcels.
public record BindingResult(List<BindOutcome> bound, List<BindOutcome> overflowed, List<BindOutcome> unresolved) {

    public static BindingResult from(List<BindOutcome> outcomes) {
        return new BindingResult(
                outcomes.stream().filter(o -> o.outcome() == BindOutcome.Outcome.BOUND).toList(),
                outcomes.stream().filter(o -> o.outcome() == BindOutcome.Outcome.OVERFLOW).toList(),
                outcomes.stream().filter(o -> o.outcome() == BindOutcome.Outcome.UNRESOLVED).toList());
    }
}
