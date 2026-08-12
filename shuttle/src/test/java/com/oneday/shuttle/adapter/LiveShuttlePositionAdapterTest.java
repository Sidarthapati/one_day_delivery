package com.oneday.shuttle.adapter;

import com.oneday.common.port.LivePosition;
import com.oneday.shuttle.domain.ShuttleDirection;
import com.oneday.shuttle.domain.ShuttleLeg;
import com.oneday.shuttle.domain.ShuttleLiveStatus;
import com.oneday.shuttle.repository.ShuttleLegRepository;
import com.oneday.shuttle.repository.ShuttleLiveStatusRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LiveShuttlePositionAdapterTest {

    @Mock ShuttleLegRepository legRepository;
    @Mock ShuttleLiveStatusRepository liveStatusRepository;

    private LiveShuttlePositionAdapter adapter() {
        return new LiveShuttlePositionAdapter(legRepository, liveStatusRepository);
    }

    @Test
    void resolvesTheCarryingAgentsGps() {
        UUID parcel = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        when(legRepository.findFirstByParcelIdOrderByCreatedAtDesc(parcel)).thenReturn(Optional.of(
                ShuttleLeg.builder().parcelId(parcel).agentId(agent).direction(ShuttleDirection.OUTBOUND).build()));
        when(liveStatusRepository.findById(agent)).thenReturn(Optional.of(ShuttleLiveStatus.builder()
                .agentId(agent).lastLat(17.45).lastLon(78.46).lastSeenAt(Instant.now()).build()));

        Optional<LivePosition> pos = adapter().forShipment(parcel);

        assertThat(pos).isPresent();
        assertThat(pos.get().lat()).isEqualTo(17.45);
        assertThat(pos.get().sourceId()).isEqualTo(agent);
    }

    @Test
    void emptyWhenParcelNeverOnAShuttle() {
        UUID parcel = UUID.randomUUID();
        when(legRepository.findFirstByParcelIdOrderByCreatedAtDesc(parcel)).thenReturn(Optional.empty());
        assertThat(adapter().forShipment(parcel)).isEmpty();
    }

    @Test
    void emptyWhenAgentHasNoGpsFix() {
        UUID parcel = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        when(legRepository.findFirstByParcelIdOrderByCreatedAtDesc(parcel)).thenReturn(Optional.of(
                ShuttleLeg.builder().parcelId(parcel).agentId(agent).build()));
        when(liveStatusRepository.findById(agent)).thenReturn(Optional.of(
                ShuttleLiveStatus.builder().agentId(agent).build()));   // no lat/lon
        assertThat(adapter().forShipment(parcel)).isEmpty();
    }
}
