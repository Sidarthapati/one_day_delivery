package com.oneday.orders.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Admin: batch all of a vendor's available COD into a new remittance. */
public record CreateRemittanceRequest(@NotNull UUID b2bAccountId) {}
