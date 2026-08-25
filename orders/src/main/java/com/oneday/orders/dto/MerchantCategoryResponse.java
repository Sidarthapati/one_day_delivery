package com.oneday.orders.dto;

import com.oneday.orders.domain.MerchantCategory;

import java.util.UUID;

/** Read model for a merchant category. Snake_case on the wire (project-wide Jackson strategy). */
public record MerchantCategoryResponse(UUID id, String name) {
    public static MerchantCategoryResponse from(MerchantCategory c) {
        return new MerchantCategoryResponse(c.getId(), c.getName());
    }
}
