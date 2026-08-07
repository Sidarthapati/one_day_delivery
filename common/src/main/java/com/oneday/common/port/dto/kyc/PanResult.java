package com.oneday.common.port.dto.kyc;

/** Result of a PAN verification. {@code nameMatch} reflects the name-on-PAN cross-check. */
public record PanResult(
        boolean verified,
        String pan,
        String registeredName,
        boolean nameMatch,
        String message
) {
    public static PanResult failed(String pan, String message) {
        return new PanResult(false, pan, null, false, message);
    }
}
