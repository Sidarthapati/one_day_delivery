package com.oneday.airline.service;

import com.oneday.airline.domain.Awb;
import com.oneday.airline.domain.AwbParcel;
import com.oneday.airline.events.CustodyScanProducer;
import com.oneday.airline.repository.AwbParcelRepository;
import com.oneday.airline.repository.AwbRepository;
import com.oneday.common.kafka.enums.ScanEventType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AirlineCustodyServiceTest {

    private final AwbParcelRepository awbParcelRepository = mock(AwbParcelRepository.class);
    private final AwbRepository awbRepository = mock(AwbRepository.class);
    private final CustodyScanProducer scanProducer = mock(CustodyScanProducer.class);
    private final AirlineCustodyService service =
            new AirlineCustodyService(awbParcelRepository, awbRepository, scanProducer);

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
        // Only DEST_SHUTTLE_IN stamps the AWB — an origin-out never touches dest custody.
        verify(awbRepository, never()).save(any());
    }

    @Test
    void unknownAwb_scansNothing() {
        UUID awbId = UUID.randomUUID();
        when(awbParcelRepository.findByAwbId(awbId)).thenReturn(List.of());

        assertThat(service.record(awbId, ScanEventType.HUB_DEST_IN)).isZero();
        verifyNoInteractions(scanProducer);
    }

    @Test
    void destShuttleIn_stampsDestCollectedAt() {
        UUID awbId = UUID.randomUUID();
        Awb awb = new Awb();
        when(awbParcelRepository.findByAwbId(awbId)).thenReturn(List.of(parcel(UUID.randomUUID())));
        when(awbRepository.findById(awbId)).thenReturn(Optional.of(awb));

        service.record(awbId, ScanEventType.DEST_SHUTTLE_IN);

        assertThat(awb.getDestCollectedAt()).isNotNull();
        verify(awbRepository).save(awb);
    }

    @Test
    void destShuttleIn_isIdempotent_doesNotRestampAnAlreadyCollectedAwb() {
        UUID awbId = UUID.randomUUID();
        Awb awb = new Awb();
        awb.setDestCollectedAt(java.time.Instant.parse("2026-08-12T20:00:00Z"));
        when(awbParcelRepository.findByAwbId(awbId)).thenReturn(List.of(parcel(UUID.randomUUID())));
        when(awbRepository.findById(awbId)).thenReturn(Optional.of(awb));

        service.record(awbId, ScanEventType.DEST_SHUTTLE_IN);

        assertThat(awb.getDestCollectedAt()).isEqualTo(java.time.Instant.parse("2026-08-12T20:00:00Z"));
        verify(awbRepository, never()).save(any());
    }

    @Test
    void destShuttleIn_unknownAwb_stampsNothing() {
        UUID awbId = UUID.randomUUID();
        when(awbParcelRepository.findByAwbId(awbId)).thenReturn(List.of());

        service.record(awbId, ScanEventType.DEST_SHUTTLE_IN);

        // No parcels → nothing scanned, and we never look up / stamp the AWB.
        verify(awbRepository, never()).findById(any());
        verify(awbRepository, never()).save(any());
    }
}
