package com.oneday.vision;

/**
 * Outcome of a dimension measurement. On {@link Status#OK} {@code lengthCm} and {@code widthCm} are
 * always present (centimetres); {@code heightCm} is present only when a side capture succeeded and is
 * null for the top-only flow. Longest-first is NOT guaranteed — they map to the parcel axes as
 * measured. For any non-OK status all three dimensions are null and {@link #status()} explains why.
 *
 * @param status     outcome
 * @param lengthCm   measured length in cm (null unless OK)
 * @param widthCm    measured width in cm (null unless OK)
 * @param heightCm   measured height in cm (null unless OK)
 * @param confidence 0..1 quality score (marker count, reprojection error, view coverage)
 * @param detail     human-readable note for logs / the DA app (e.g. "no marker detected")
 */
public record MeasurementResult(
        Status status,
        Double lengthCm,
        Double widthCm,
        Double heightCm,
        double confidence,
        String detail) {

    public enum Status {
        /** Dimensions produced. */
        OK,
        /** Native CV library not loaded — the whole engine is unavailable. */
        ENGINE_UNAVAILABLE,
        /** No ArUco marker found in the photo(s), so there is no scale reference. */
        NO_MARKER,
        /** A marker was found but the parcel edges could not be resolved reliably. */
        LOW_CONFIDENCE,
        /** Bad input (unreadable image, no captures). */
        BAD_INPUT,
        /** CV ran too long and was aborted. */
        TIMEOUT,
        /** Any unexpected engine failure. */
        ERROR
    }

    public boolean isOk() {
        return status == Status.OK;
    }

    public static MeasurementResult ok(double l, double w, Double h, double confidence, String detail) {
        return new MeasurementResult(Status.OK, l, w, h, confidence, detail);
    }

    public static MeasurementResult failed(Status status, String detail) {
        return new MeasurementResult(status, null, null, null, 0.0, detail);
    }
}
