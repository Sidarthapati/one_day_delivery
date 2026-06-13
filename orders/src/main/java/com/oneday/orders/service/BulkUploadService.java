package com.oneday.orders.service;

import com.oneday.orders.dto.BulkPickup;
import com.oneday.orders.dto.BulkUploadResponse;

import java.io.InputStream;
import java.util.UUID;

/**
 * Excel bulk upload for the cart, modelled as <b>one shared pickup → many destinations</b>: the
 * user sets a single pickup on the booking map and uploads a sheet of destination rows. Each row
 * is validated (field constraints + serviceability/pricing), and valid rows are added to the cart
 * with the shared pickup applied; invalid rows are reported with reasons. Destinations are
 * pincode-based (no coordinates in the sheet) — the city is derived from the pincode prefix.
 */
public interface BulkUploadService {

    /** The fixed destination column headers, in order. The uploaded sheet must match exactly. */
    java.util.List<String> headers();

    /** Generates the template workbook (headers + one example row) as xlsx bytes. */
    byte[] generateTemplate();

    /** Parses + validates the destinations, applying {@code pickup} to each and adding valid rows to the cart. */
    BulkUploadResponse process(UUID userId, BulkPickup pickup, InputStream xlsx);

    /** The sheet's header row does not match the template, or it exceeds the row cap → 422. */
    class BadTemplateException extends RuntimeException {
        public BadTemplateException(String message) { super(message); }
    }
}
