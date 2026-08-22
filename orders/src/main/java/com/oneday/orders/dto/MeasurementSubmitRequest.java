package com.oneday.orders.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** DA submits the uploaded evidence keys (+ which face each frames) for server-side measurement. */
public record MeasurementSubmitRequest(
        @NotEmpty List<EvidenceCapture> captures) {}
