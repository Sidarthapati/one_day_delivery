package com.oneday.orders.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Admin: confirm a remittance's bank transfer with its UTR. */
public record MarkRemittancePaidRequest(@NotBlank @Size(max = 50) String utr) {}
