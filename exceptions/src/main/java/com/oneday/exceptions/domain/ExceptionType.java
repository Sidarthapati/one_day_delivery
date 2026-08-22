package com.oneday.exceptions.domain;

/** What kind of failure opened the case — drives which resolve actions apply. */
public enum ExceptionType {
    PICKUP_FAILED,
    DELIVERY_FAILED,
    CRON_MISSED,
    FLIGHT_MISSED
}
