package com.oneday.common.port;

import com.oneday.common.port.dto.einvoice.EInvoiceRequest;
import com.oneday.common.port.dto.einvoice.EInvoiceResult;

/**
 * Government e-invoicing (IRP/IRN) — the swappable seam for the legally-mandated part of invoicing
 * that cannot be self-generated. The default bean is a no-op ({@code registered=false}) for the
 * pilot, where we are below the e-invoicing turnover threshold and/or not yet GST-registered. When
 * we cross the threshold, a real GSP/ASP adapter (Sandbox / ClearTax) swaps in behind this port —
 * document generation and numbering stay ours; only IRN/QR come from here.
 */
public interface EInvoicePort {

    /** Register an invoice with the IRP and return its IRN + signed QR, or a not-registered result. */
    EInvoiceResult registerInvoice(EInvoiceRequest request);
}
