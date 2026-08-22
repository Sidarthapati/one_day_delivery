package com.oneday.orders.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * First-mile dimension-scan tunables: discrepancy tolerance (moderate by default), evidence-upload
 * limits, and presigned-URL TTLs.
 */
@Component
@ConfigurationProperties(prefix = "measurement")
public class MeasurementProperties {

    /** Flag when measured volume exceeds declared volume by more than this percent. */
    private double tolerancePct = 10.0;

    /** ...or when any (size-sorted) side exceeds the declared side by more than this many cm. */
    private double toleranceSideCm = 2.0;

    /** Volumetric divisor (cm³/kg). L*W*H / divisor = volumetric kg; matches M4 booking math. */
    private int volumetricDivisor = 5000;

    /** Max evidence photos accepted per measurement. */
    private int maxEvidencePhotos = 4;

    /** TTL (seconds) of the presigned PUT URLs handed to the app for upload. */
    private long uploadUrlTtlSeconds = 300;

    /** TTL (seconds) of the presigned GET URLs handed to the ops console for viewing. */
    private long viewUrlTtlSeconds = 600;

    public double getTolerancePct() { return tolerancePct; }
    public void setTolerancePct(double tolerancePct) { this.tolerancePct = tolerancePct; }

    public double getToleranceSideCm() { return toleranceSideCm; }
    public void setToleranceSideCm(double toleranceSideCm) { this.toleranceSideCm = toleranceSideCm; }

    public int getVolumetricDivisor() { return volumetricDivisor; }
    public void setVolumetricDivisor(int volumetricDivisor) { this.volumetricDivisor = volumetricDivisor; }

    public int getMaxEvidencePhotos() { return maxEvidencePhotos; }
    public void setMaxEvidencePhotos(int maxEvidencePhotos) { this.maxEvidencePhotos = maxEvidencePhotos; }

    public long getUploadUrlTtlSeconds() { return uploadUrlTtlSeconds; }
    public void setUploadUrlTtlSeconds(long uploadUrlTtlSeconds) { this.uploadUrlTtlSeconds = uploadUrlTtlSeconds; }

    public long getViewUrlTtlSeconds() { return viewUrlTtlSeconds; }
    public void setViewUrlTtlSeconds(long viewUrlTtlSeconds) { this.viewUrlTtlSeconds = viewUrlTtlSeconds; }
}
