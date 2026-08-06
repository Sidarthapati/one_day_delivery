package com.oneday.orders.dto;

import com.oneday.orders.domain.SalesLead;

import java.time.Instant;
import java.util.UUID;

public record SalesLeadResponse(
        UUID id,
        String name,
        String company,
        String email,
        String phone,
        String monthlyVolume,
        String message,
        String status,
        Instant createdAt) {

    public static SalesLeadResponse from(SalesLead l) {
        return new SalesLeadResponse(l.getId(), l.getName(), l.getCompany(), l.getEmail(),
                l.getPhone(), l.getMonthlyVolume(), l.getMessage(), l.getStatus().name(), l.getCreatedAt());
    }
}
