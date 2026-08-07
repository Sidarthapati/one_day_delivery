package com.oneday.airline.service.impl;

import com.oneday.airline.domain.Awb;
import com.oneday.airline.repository.AwbRepository;
import com.oneday.airline.service.exception.AwbNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AwbGroundServiceImplTest {

    @Mock AwbRepository awbRepository;

    private final Instant now = Instant.parse("2026-07-20T05:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final UUID awbId = UUID.randomUUID();

    private AwbGroundServiceImpl service() {
        return new AwbGroundServiceImpl(awbRepository, clock);
    }

    @Test
    void handOver_stampsTheCurrentTime() {
        Awb awb = new Awb();
        when(awbRepository.findById(awbId)).thenReturn(Optional.of(awb));
        when(awbRepository.save(any(Awb.class))).thenAnswer(inv -> inv.getArgument(0));

        Awb result = service().handOver(awbId);

        assertThat(result.getHandedOverAt()).isEqualTo(now);
        assertThat(result.getLoadedAt()).isNull();
    }

    @Test
    void markLoaded_stampsTheCurrentTime() {
        Awb awb = new Awb();
        when(awbRepository.findById(awbId)).thenReturn(Optional.of(awb));
        when(awbRepository.save(any(Awb.class))).thenAnswer(inv -> inv.getArgument(0));

        Awb result = service().markLoaded(awbId);

        assertThat(result.getLoadedAt()).isEqualTo(now);
    }

    @Test
    void unknownAwb_throws() {
        when(awbRepository.findById(awbId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().handOver(awbId)).isInstanceOf(AwbNotFoundException.class);
    }
}
