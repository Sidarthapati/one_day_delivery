package com.oneday.orders.repository;

/**
 * Projection for the merchant-analytics on-time metric: how many of an account's parcels reached a
 * delivered terminal state with a promised ETA, and how many did so on or before that ETA. Alias
 * names in the JPQL ({@code AS delivered}, {@code AS onTime}) must match these getters.
 */
public interface OnTimeStat {
    long getDelivered();
    long getOnTime();
}
