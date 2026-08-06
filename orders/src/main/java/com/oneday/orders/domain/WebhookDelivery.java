package com.oneday.orders.domain;

import com.oneday.common.domain.MutableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/** One outbound webhook POST attempt to a merchant's registered endpoint. */
@Entity
@Table(name = "webhook_delivery")
@Getter
@Setter
@NoArgsConstructor
public class WebhookDelivery extends MutableBaseEntity {

    @Column(name = "b2b_account_id", nullable = false, updatable = false)
    private UUID b2bAccountId;

    @Column(name = "event", length = 50, nullable = false, updatable = false)
    private String event;

    @Column(name = "shipment_ref", length = 30, updatable = false)
    private String shipmentRef;

    @Column(name = "url", length = 500, nullable = false, updatable = false)
    private String url;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private WebhookDeliveryStatus status = WebhookDeliveryStatus.PENDING;

    @Column(name = "response_code")
    private Integer responseCode;

    @Column(name = "attempts", nullable = false)
    private Integer attempts = 0;

    @Column(name = "payload", columnDefinition = "text", updatable = false)
    private String payload;

    @Column(name = "error", length = 500)
    private String error;
}
