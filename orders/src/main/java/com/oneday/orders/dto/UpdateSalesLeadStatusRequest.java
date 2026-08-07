package com.oneday.orders.dto;

import jakarta.validation.constraints.NotBlank;

/** Admin update of a lead's status (NEW | CONTACTED | WON | LOST). */
public class UpdateSalesLeadStatusRequest {

    @NotBlank private String status;

    public String getStatus()          { return status; }
    public void setStatus(String v)    { this.status = v; }
}
