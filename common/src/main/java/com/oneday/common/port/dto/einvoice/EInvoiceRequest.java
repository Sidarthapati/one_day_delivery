package com.oneday.common.port.dto.einvoice;

/** Minimal payload the IRP needs to register an invoice and mint an IRN. */
public record EInvoiceRequest(
        String invoiceNumber,
        String buyerGstin,     // null for an unregistered (B2C) buyer
        String sacCode,
        long taxableValuePaise,
        long cgstPaise,
        long sgstPaise,
        long igstPaise,
        long totalPaise
) {}
