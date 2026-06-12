package com.oneday.orders.dto;

import java.util.List;

/**
 * Outcome of an Excel bulk upload. Valid rows are added to the cart ({@code added}); invalid
 * rows are reported with their 1-based spreadsheet row number and the per-field reasons so the
 * UI can show a dismissable error list.
 */
public record BulkUploadResponse(
        int added,
        int failed,
        List<RowFailure> failures
) {
    public record RowFailure(int row, List<FieldError> errors) {}

    public record FieldError(String field, String reason) {}
}
