package com.oneday.common.kafka.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.oneday.common.domain.enums.ShipmentState;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ShipmentCancelledEvent extends BaseShipmentEvent {

    private ShipmentState cancelledAtState;
    private String reason;
    private Boolean refundInitiated;
    private Long refundAmountPaise;
}
