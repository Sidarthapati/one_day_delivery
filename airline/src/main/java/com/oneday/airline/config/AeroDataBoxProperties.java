package com.oneday.airline.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AeroDataBox integration config — the real flight-schedule + status feed behind {@code
 * FlightProviderPort}, kept swappable and <b>off by default</b> (mirrors {@code RazorpayProperties.live}).
 *
 * <p>Nothing here is active until {@code enabled=true} AND an {@code apiKey} is supplied via env
 * ({@code AIRLINE_AERODATABOX_ENABLED} / {@code AIRLINE_AERODATABOX_API_KEY}) — until then the app runs
 * on the synthetic consolidator exactly as before. <b>Never commit the key.</b></p>
 *
 * <p>Cost model (see project docs): schedule ingest + the daily disruption poll are the only calls that
 * spend AeroDataBox units; flight <em>selection</em> reads our own ingested {@code flight_leg} store and
 * live position is interpolated, both zero-unit.</p>
 */
@Component
@ConfigurationProperties(prefix = "airline.aerodatabox")
@Data
public class AeroDataBoxProperties {

    /** Master switch. False → the synthetic consolidator provider stays primary; no HTTP calls are made. */
    private boolean enabled = false;

    /** RapidAPI (or direct) key. Env-only — never commit. */
    private String apiKey = "";

    /** Base URL of the AeroDataBox API. Default is the RapidAPI gateway. */
    private String baseUrl = "https://aerodatabox.p.rapidapi.com";

    /** RapidAPI host header value (ignored for a direct subscription with an empty value). */
    private String rapidApiHost = "aerodatabox.p.rapidapi.com";

    /** How many days of forward schedule the monthly ingest projects. Default 1 month (less speculative booking). */
    private int scheduleHorizonDays = 30;

    /**
     * Block-time estimate (minutes) used to derive an arrival time from a FIDS departure row, since a
     * departures query doesn't carry the arrival time. Corrected by the daily status poll's real
     * estimated arrival. A single value is fine for the 5 metro lanes (all ~2h); refine per-lane later.
     */
    private int defaultBlockMinutes = 150;
}
