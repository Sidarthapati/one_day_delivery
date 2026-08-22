package com.oneday.vision;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tunables for the ArUco dimension engine. The marker's real-world side length is the scale
 * reference, so it MUST match the printed board exactly.
 */
@Component
@ConfigurationProperties(prefix = "vision")
public class VisionProperties {

    /** Physical side length of one printed ArUco marker, in cm. Must match the board. */
    private double markerSizeCm = 5.0;

    /** Predefined ArUco dictionary id (OpenCV DICT_* ordinal). 4 = DICT_5X5_50 (the fleet marker). */
    private int dictionaryId = 4;

    /** Hard cap on a single measurement (ms); exceeding it aborts to a TIMEOUT result. */
    private long timeoutMs = 8000;

    /** Longest image edge (px) the engine works at; larger inputs are downscaled first. */
    private int maxImageEdgePx = 2000;

    /** Below this confidence the result is reported LOW_CONFIDENCE rather than OK. */
    private double minConfidence = 0.35;

    public double getMarkerSizeCm() { return markerSizeCm; }
    public void setMarkerSizeCm(double markerSizeCm) { this.markerSizeCm = markerSizeCm; }

    public int getDictionaryId() { return dictionaryId; }
    public void setDictionaryId(int dictionaryId) { this.dictionaryId = dictionaryId; }

    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }

    public int getMaxImageEdgePx() { return maxImageEdgePx; }
    public void setMaxImageEdgePx(int maxImageEdgePx) { this.maxImageEdgePx = maxImageEdgePx; }

    public double getMinConfidence() { return minConfidence; }
    public void setMinConfidence(double minConfidence) { this.minConfidence = minConfidence; }
}
