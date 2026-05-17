package com.oneday.common.kafka.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.oneday.common.domain.enums.ShipmentState;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

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
}
