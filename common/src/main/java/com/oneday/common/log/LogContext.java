package com.oneday.common.log;

import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;

/**
 * Scoped MDC helper. Puts correlation keys ({@code shipmentId}/{@code shipmentRef}/{@code parcelId}/…)
 * into the SLF4J {@link MDC} for the duration of a try-with-resources block, so every log line
 * emitted inside — including the scattered {@code log.info(...)} calls in the services this unit of
 * work touches — carries those fields (rendered by the JSON encoder / the MDC pattern). Cleared on
 * {@link #close()} so values never leak to the next task on a pooled thread.
 *
 * <pre>{@code
 * try (var ignored = LogContext.forShipment(shipmentId, shipmentRef, parcelId)) {
 *     ...  // all logs here are tagged
 * }
 * }</pre>
 *
 * <p>Generalises the ad-hoc {@code MDC.put/remove} block that used to live in
 * {@code DispatchServiceImpl.tracked(...)}.</p>
 */
public final class LogContext implements AutoCloseable {

    private final List<String> keys = new ArrayList<>(4);

    private LogContext() {
    }

    public static LogContext of() {
        return new LogContext();
    }

    /** Convenience for the common shipment/parcel correlation triple. Null parts are skipped. */
    public static LogContext forShipment(Object shipmentId, String shipmentRef, Object parcelId) {
        return of()
                .put("shipmentId", str(shipmentId))
                .put("shipmentRef", shipmentRef)
                .put("parcelId", str(parcelId));
    }

    /** Put a key into the MDC (no-op if {@code value} is null); remembered for cleanup. */
    public LogContext put(String key, String value) {
        if (value != null) {
            MDC.put(key, value);
            keys.add(key);
        }
        return this;
    }

    @Override
    public void close() {
        keys.forEach(MDC::remove);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
