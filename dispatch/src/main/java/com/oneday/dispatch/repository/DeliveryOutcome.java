package com.oneday.dispatch.repository;

/**
 * Delivery attempt outcome for a city/date: how many DELIVERY tasks were COMPLETED vs FAILED. Feeds the
 * attempt-success gauge ({@code completed / (completed + failed)}). Native aliases {@code AS completed},
 * {@code AS failed} map here.
 */
public interface DeliveryOutcome {
    long getCompleted();
    long getFailed();
}
