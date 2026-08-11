package com.oneday.airline.service;

import com.oneday.airline.domain.AwbParcel;
import com.oneday.airline.events.CustodyScanProducer;
import com.oneday.airline.repository.AwbParcelRepository;
import com.oneday.common.kafka.enums.ScanEventType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AirlineCustodyServiceTest {

    private final AwbParcelRepository awbParcelRepository = mock(AwbParcelRepository.class);
    private final CustodyScanProducer scanProducer = mock(CustodyScanProducer.class);
    private final AirlineCustodyService service = new AirlineCustodyService(awbParcelRepository, scanProducer);

    private AwbParcel parcel(UUID parcelId) {
        AwbParcel p = new AwbParcel();
        p.setParcelId(parcelId);
        return p;
    }

    @Test
    void firesTheScanForEveryParcelOnTheAwb() {
        UUID awbId = UUID.randomUUID();
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        when(awbParcelRepository.findByAwbId(awbId)).thenReturn(List.of(parcel(p1), parcel(p2)));

        int scanned = service.record(awbId, ScanEventType.HUB_ORIGIN_OUT);

        assertThat(scanned).isEqualTo(2);
        verify(scanProducer).publish(p1, ScanEventType.HUB_ORIGIN_OUT);
        verify(scanProducer).publish(p2, ScanEventType.HUB_ORIGIN_OUT);
    }

    @Test
    void unknownAwb_scansNothing() {
        UUID awbId = UUID.randomUUID();
        when(awbParcelRepository.findByAwbId(awbId)).thenReturn(List.of());

        assertThat(service.record(awbId, ScanEventType.HUB_DEST_IN)).isZero();
        verifyNoInteractions(scanProducer);
    }
}
