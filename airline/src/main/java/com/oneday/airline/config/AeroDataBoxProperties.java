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
     * Minimum gap between <em>any</em> two AeroDataBox HTTP calls (schedule ingest AND status poll),
     * enforced inside the client so no caller can exceed the plan's requests/second cap — RapidAPI
     * throttles all AeroDataBox tiers at ~2 req/s. Default 550 ms (~1.8/s, with margin).
     */
    private long minRequestIntervalMs = 550;

    /**
     * Pause between successive FIDS calls during a schedule ingest, to respect a plan's requests/second
     * cap (RapidAPI's free BASIC tier is ~1 req/s). Default 0 (paid tiers with a high rate limit); set to
     * ~1100 ms when testing on the free tier so the ingest doesn't get 429-throttled.
     */
    private long interCallDelayMs = 0;
}
