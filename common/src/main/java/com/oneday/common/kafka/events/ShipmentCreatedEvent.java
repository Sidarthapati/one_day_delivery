package com.oneday.common.kafka.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.DropType;
import com.oneday.common.domain.enums.PaymentMode;
import com.oneday.common.domain.enums.PickupType;
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
public class ShipmentCreatedEvent extends BaseShipmentEvent {

    private CustomerType customerType;
    private PaymentMode paymentMode;
    private DeliveryType deliveryType;
    private PickupType pickupType;   // DA_PICKUP (default) or SELF_DROP
    private DropType dropType;       // DA_DELIVERY (default) or HUB_COLLECT
    private String originCity;
    private String originPincode;
    private UUID originTileId;
    private Double originLat;
    private Double originLon;
    private String destCity;
    private String destPincode;
    private Double destLat;
    private Double destLon;
    private Integer chargeableWeightGrams;
    private Integer slaCommitmentMinutes;
    private Instant etaPromised;
    private String receiverPhone;
    private String receiverName;
    private UUID b2bAccountId;
}
