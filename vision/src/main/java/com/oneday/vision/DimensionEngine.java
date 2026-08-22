package com.oneday.vision;

import java.util.List;

/**
 * Measures a parcel's physical dimensions from photos that include a known-size ArUco reference
 * marker. The marker supplies real-world scale, so the result is metric (centimetres) and largely
 * independent of the (unknown, cheap) phone camera.
 *
 * <p>The engine is <b>best-effort and never throws</b>: any failure — native library missing, no
 * marker detected, a corrupt image, a timeout — comes back as a {@link MeasurementResult} with
 * {@link MeasurementResult#status()} != {@code OK}. Callers store the evidence photos regardless and
 * degrade gracefully; a measurement failure must never break the pickup flow.</p>
 */
public interface DimensionEngine {

    /** True when the native CV library loaded successfully at startup. */
    boolean isAvailable();

    /**
     * Measure from one or more captures of the same parcel on the marker.
     *
     * @param captures the guided photos (v1: index 0 = top-down for L×W, index 1 = side for H)
     * @return the measurement, or a non-OK result explaining why it could not be produced
     */
    MeasurementResult measure(List<Capture> captures);

    /** One captured image plus which face it frames. */
    record Capture(byte[] imageBytes, View view) {}

    /** Which parcel face a capture frames, so the engine knows what to read from it. */
    enum View { TOP, SIDE, UNKNOWN }
}
