package com.oneday.shuttle.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * M12 shuttle knobs. Defaults so no yaml is needed to boot. The seal buffer lives on the hub side
 * (its AutoSealJob owns sealing); here we only need what shapes the queue's leave-by + GPS staleness.
 */
@Component
@ConfigurationProperties(prefix = "shuttle")
@Data
public class ShuttleProperties {

    /** Minutes the hub→airport drive takes; feeds leave-by = bagCutoff − hubToAirport − loadBuffer. */
    private int hubToAirportMinutes = 60;

    /** Slack added on top of the drive so the agent leaves with time to load. */
    private int loadBufferMinutes = 15;

    /** A shuttle GPS fix older than this is considered stale (the dot degrades to the airport pin). */
    private long gpsStaleSeconds = 120;
}
