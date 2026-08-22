package com.oneday.orders.domain;

/** How a measurement's numbers were produced. */
public enum MeasurementMethod {
    /** Server-side OpenCV/ArUco from photos. */
    ARUCO,
    /** Hand-entered by an operator. */
    MANUAL,
    /** Copied from the customer's declaration. */
    DECLARED
}
