package com.oneday.orders.dto;

import java.time.Instant;

/** A tax invoice as returned to the customer (consumer or business). */
public record InvoiceResponse(
        String invoiceNumber,
        String shipmentRef,
        String customerType,
        String buyerName,
        String buyerGstin,
        String sacCode,
        long taxableValuePaise,
        long cgstPaise,
        long sgstPaise,
        long igstPaise,
        long totalPaise,
        boolean eInvoiced,
        String irn,
        Instant issuedAt
) {}
