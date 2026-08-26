package com.oneday.orders.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Create/rename payload for a merchant category. */
public class MerchantCategoryRequest {

    @NotBlank
    @Size(max = 60)
    private String name;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
