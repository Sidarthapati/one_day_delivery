package com.oneday.exceptions.domain;

/** How a support ticket was raised. */
public enum TicketChannel {
    /** A written help request (subject + body), optionally about a shipment. */
    TICKET,
    /** A "call me" callback request — the agent phones the reporter back. */
    CALLBACK
}
