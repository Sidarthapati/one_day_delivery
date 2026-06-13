package com.oneday.orders.dto;

import com.oneday.orders.domain.SavedAddress;
import com.oneday.orders.domain.enums.AddressLabel;

import java.util.UUID;

/**
 * Read model for a saved address. Snake_case on the wire (project-wide Jackson strategy).
 */
public record SavedAddressResponse(
        UUID id,
        AddressLabel label,
        String saveAs,
        String contactName,
        String contactPhone,
        String houseFloor,
        String buildingStreet,
        String areaLocality,
        String line1,
        String line2,
        String city,
        String pincode,
        String state,
        String landmark,
        Double latitude,
        Double longitude,
        String deliveryInstructions
) {
    public static SavedAddressResponse from(SavedAddress a) {
        return new SavedAddressResponse(
                a.getId(), a.getLabel(), a.getSaveAs(), a.getContactName(), a.getContactPhone(),
                a.getHouseFloor(), a.getBuildingStreet(), a.getAreaLocality(),
                a.getLine1(), a.getLine2(), a.getCity(), a.getPincode(), a.getState(), a.getLandmark(),
                a.getLatitude(), a.getLongitude(), a.getDeliveryInstructions());
    }
}
