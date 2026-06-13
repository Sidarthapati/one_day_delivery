package com.oneday.orders.dto;

import com.oneday.orders.domain.Address;

/**
 * The single shared pickup for a bulk upload (one pickup → many destinations). Sent alongside
 * the destinations spreadsheet; applied to every row so the user sets the pickup just once on
 * the booking map. Sender name/phone come from the booking form (their account).
 */
public record BulkPickup(
        String senderName,
        String senderPhone,
        String senderEmail,
        Address originAddress,
        String originCity,
        String originPincode
) {}
