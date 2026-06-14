package com.oneday.pricing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Default rate-card parameters applied when an admin publishes a new card without overriding them.
 * Mirror the published sheet; override via {@code pricing.*} in application.yml.
 */
@Component
@ConfigurationProperties(prefix = "pricing")
public class PricingProperties {

    /** Weight slab size in grams (0.5 kg). */
    private int slabGrams = 500;
    /** Volumetric divisor (L×W×H / divisor). */
    private int volumetricDivisor = 5000;
    private int firstSlabPct = 100;
    private int slabDecrementPct = 10;
    private int slabFloorPct = 60;
    /** GST basis points (18%). */
    private int gstBps = 1800;
    /** COD charge basis points of declared value (1.5%). */
    private int codPctBps = 150;
    /** Minimum COD charge in paise (₹50). */
    private long codMinPaise = 5000L;
    /** Default same-city first-slab base price in paise (not in the published sheet). */
    private long sameCityBasePricePaise = 5000L;
    private String currency = "INR";

    public int getSlabGrams() { return slabGrams; }
    public void setSlabGrams(int v) { this.slabGrams = v; }
    public int getVolumetricDivisor() { return volumetricDivisor; }
    public void setVolumetricDivisor(int v) { this.volumetricDivisor = v; }
    public int getFirstSlabPct() { return firstSlabPct; }
    public void setFirstSlabPct(int v) { this.firstSlabPct = v; }
    public int getSlabDecrementPct() { return slabDecrementPct; }
    public void setSlabDecrementPct(int v) { this.slabDecrementPct = v; }
    public int getSlabFloorPct() { return slabFloorPct; }
    public void setSlabFloorPct(int v) { this.slabFloorPct = v; }
    public int getGstBps() { return gstBps; }
    public void setGstBps(int v) { this.gstBps = v; }
    public int getCodPctBps() { return codPctBps; }
    public void setCodPctBps(int v) { this.codPctBps = v; }
    public long getCodMinPaise() { return codMinPaise; }
    public void setCodMinPaise(long v) { this.codMinPaise = v; }
    public long getSameCityBasePricePaise() { return sameCityBasePricePaise; }
    public void setSameCityBasePricePaise(long v) { this.sameCityBasePricePaise = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
}
