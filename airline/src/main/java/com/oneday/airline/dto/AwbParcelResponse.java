package com.oneday.airline.dto;

import com.oneday.airline.domain.AwbParcel;

import java.util.UUID;

/** One manifest line — a parcel's share of the AWB, per §6/§10's weight-proportional cost split. */
public record AwbParcelResponse(
        UUID parcelId,
        String shipmentRef,
        int weightGrams,
        long allocatedCostPaise) {

    public static AwbParcelResponse from(AwbParcel p) {
        return new AwbParcelResponse(p.getParcelId(), p.getShipmentRef(), p.getWeightGrams(), p.getAllocatedCostPaise());
    }
}
