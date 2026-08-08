package com.oneday.common.log;

import com.oneday.common.kafka.DomainEvent;
import com.oneday.common.kafka.enums.DaEventType;
import com.oneday.common.kafka.enums.HubEventType;
import com.oneday.common.kafka.enums.ScanEventType;
import com.oneday.common.kafka.events.DaLifecycleEvent;
import com.oneday.common.kafka.events.HubEvent;
import com.oneday.common.kafka.events.ScanEvent;
import com.oneday.common.kafka.events.ShipmentCreatedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationKeysTest {

    @Test
    void shipmentEvent_extractsIdAndRef() {
        UUID sid = UUID.randomUUID();
        ShipmentCreatedEvent e = new ShipmentCreatedEvent();
        e.setShipmentId(sid);
        e.setShipmentRef("1DD-BLR-20260530-00042");

        CorrelationKeys k = CorrelationKeys.from(e);

        assertThat(k.shipmentId()).isEqualTo(sid.toString());
        assertThat(k.shipmentRef()).isEqualTo("1DD-BLR-20260530-00042");
        assertThat(k.parcelId()).isNull();
        assertThat(k.isEmpty()).isFalse();
    }

    @Test
    void scanEvent_extractsIdAndParcel() {
        UUID sid = UUID.randomUUID();
        ScanEvent e = new ScanEvent(sid, ScanEventType.LABEL_GENERATED, "1DD-DEL-260530-000123", Instant.now());

        CorrelationKeys k = CorrelationKeys.from(e);

        assertThat(k.shipmentId()).isEqualTo(sid.toString());
        assertThat(k.parcelId()).isEqualTo("1DD-DEL-260530-000123");
        assertThat(k.shipmentRef()).isNull();
    }

    @Test
    void daLifecycleEvent_extractsAllThree() {
        UUID sid = UUID.randomUUID();
        UUID pid = UUID.randomUUID();
        DaLifecycleEvent e = new DaLifecycleEvent(UUID.randomUUID(), DaEventType.PICKUP_COMPLETED, "1.0",
                Instant.now(), sid, "1DD-DEL-20260530-00007", UUID.randomUUID(), UUID.randomUUID(),
                null, null, null, pid, LocalDate.now());

        CorrelationKeys k = CorrelationKeys.from(e);

        assertThat(k.shipmentId()).isEqualTo(sid.toString());
        assertThat(k.shipmentRef()).isEqualTo("1DD-DEL-20260530-00007");
        assertThat(k.parcelId()).isEqualTo(pid.toString());
    }

    @Test
    void hubEvent_extractsShipmentIdOnly() {
        UUID sid = UUID.randomUUID();
        CorrelationKeys k = CorrelationKeys.from(new HubEvent(sid, HubEventType.STAND_ASSIGNED));

        assertThat(k.shipmentId()).isEqualTo(sid.toString());
        assertThat(k.shipmentRef()).isNull();
        assertThat(k.parcelId()).isNull();
    }

    @Test
    void unknownDomainEvent_fallsBackToPartitionKey() {
        DomainEvent generic = new DomainEvent() {
            @Override public String partitionKey() { return "TILE-9"; }
            @Override public String eventTypeName() { return "SOMETHING"; }
        };

        CorrelationKeys k = CorrelationKeys.from(generic);

        assertThat(k.shipmentId()).isEqualTo("TILE-9");
    }

    @Test
    void nullPayload_isEmpty() {
        assertThat(CorrelationKeys.from(null).isEmpty()).isTrue();
    }
}
