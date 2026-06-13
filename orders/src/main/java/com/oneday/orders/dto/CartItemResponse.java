package com.oneday.orders.dto;

import com.oneday.orders.domain.CartItem;

import java.util.UUID;

/** Read model for one cart line. */
public record CartItemResponse(
        UUID id,
        String source,
        Integer excelRowNum,
        String senderName,
        String senderPhone,
        String originCity,
        String originPincode,
        String receiverName,
        String receiverPhone,
        String destCity,
        String destPincode,
        int weightGrams,
        String deliveryType,
        Long quotedTotalPaise,
        String validationStatus,
        String bookedShipmentRef
) {
    public static CartItemResponse from(CartItem i) {
        return new CartItemResponse(
                i.getId(), i.getSource().name(), i.getExcelRowNum(),
                i.getSenderName(), i.getSenderPhone(), i.getOriginCity(), i.getOriginPincode(),
                i.getReceiverName(), i.getReceiverPhone(), i.getDestCity(), i.getDestPincode(),
                i.getWeightGrams(),
                i.getDeliveryType() == null ? null : i.getDeliveryType().name(),
                i.getQuotedTotalPaise(), i.getValidationStatus(), i.getBookedShipmentRef());
    }
}
