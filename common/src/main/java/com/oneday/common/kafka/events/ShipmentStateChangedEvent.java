package com.oneday.common.kafka.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.oneday.common.domain.enums.DropType;
import com.oneday.common.domain.enums.ShipmentState;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ShipmentStateChangedEvent extends BaseShipmentEvent {

    private ShipmentState fromState;
    private ShipmentState toState;
    private String triggeredBy;
    private String triggerSource;
    private Instant etaUpdated;

    // Q-M4-2 dest data: populated ONLY on the HANDED_TO_DROP_VAN transition (NON_NULL keeps other
    // transitions lean) so M5 can assign the delivery DA straight from the event, no GET back to M4.
    private Double destLat;
    private Double destLon;
    private UUID destTileId;
    private DropType dropType;
}
