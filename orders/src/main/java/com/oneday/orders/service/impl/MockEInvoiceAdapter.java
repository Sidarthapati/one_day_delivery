package com.oneday.orders.service.impl;

import com.oneday.common.port.EInvoicePort;
import com.oneday.common.port.dto.einvoice.EInvoiceRequest;
import com.oneday.common.port.dto.einvoice.EInvoiceResult;
import org.springframework.stereotype.Service;

/**
 * Pilot default {@link EInvoicePort}: does not register with the IRP (we are below the e-invoicing
 * threshold / not yet GST-registered). Invoices are still valid self-generated tax invoices. Swap a
 * real GSP/ASP adapter (Sandbox/ClearTax) behind the port when the threshold is crossed.
 */
@Service
class MockEInvoiceAdapter implements EInvoicePort {

    @Override
    public EInvoiceResult registerInvoice(EInvoiceRequest request) {
        return EInvoiceResult.notRegistered("e-invoicing not enabled for the pilot");
    }
}
