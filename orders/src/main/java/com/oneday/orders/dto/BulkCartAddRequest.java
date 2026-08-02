package com.oneday.orders.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Adds many shipment drafts to the cart in one call — the bulk-upload path. Each item is a fully
 * resolved {@link AddCartItemRequest} (the client geocodes destination addresses to real
 * coordinates before submitting). Rows are priced/serviceability-checked server-side; the
 * per-row outcome comes back so the UI can flag failures. Nothing is booked here — checkout does that.
 */
public class BulkCartAddRequest {

    @NotNull
    @Size(min = 1, max = 500, message = "must contain 1..500 items")
    @Valid
    private List<AddCartItemRequest> items;

    public List<AddCartItemRequest> getItems() { return items; }
    public void setItems(List<AddCartItemRequest> items) { this.items = items; }
}
