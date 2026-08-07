package com.oneday.orders.service;

import com.oneday.orders.dto.InvoiceResponse;

import java.util.List;
import java.util.UUID;

public interface InvoiceService {

    /** The invoice for one of the caller's shipments — generated on first fetch if it doesn't exist. */
    InvoiceResponse getForShipment(String shipmentRef, UUID callerUserId);

    /** All invoices already issued to the caller, newest first. */
    List<InvoiceResponse> listMine(UUID callerUserId);
}
