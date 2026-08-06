package com.oneday.orders.dto;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.DropType;
import com.oneday.common.domain.enums.PickupType;

import java.time.Instant;

/**
 * Everything a shipping label needs, for the "Model A" flow where the <em>merchant</em> prints our
 * label and sticks it on the parcel ({@code GET /api/v1/shipments/mine/{ref}/label}). The AWB /
 * barcode value is the shipment reference itself (always available, scannable at every hub/DA node
 * via M8) — no separate physical-label pre-assignment. Field names serialise to snake_case.
 */
public record ShipmentLabelResponse(
        // AWB — the shipment ref doubles as the human-readable AWB and the Code128 barcode value
        String awb,
        String barcodeValue,
        CustomerType customerType,
        DeliveryType deliveryType,
        PickupType pickupType,
        DropType dropType,
        // routing text (city codes) for the sort operator
        String originCity,
        String destCity,
        // FROM (seller / pickup)
        String fromName,
        String fromPhone,
        String fromAddress,
        String fromPincode,
        // TO (buyer / consignee)
        String toName,
        String toPhone,
        String toAddress,
        String toPincode,
        // parcel
        Integer weightGrams,
        Integer chargeableWeightGrams,
        Integer volumetricWeightGrams,
        Long declaredValuePaise,
        // COD to collect on delivery (null ⇒ prepaid / non-COD)
        Long codAmountPaise,
        // seller block (B2B only; null for B2C/C2C)
        String sellerName,
        String sellerGstin,
        // e-way bill (optional; > ₹50k interstate)
        String ewayBillNumber,
        Instant createdAt) {
}
