package com.oneday.dispatch.batch;

import com.oneday.common.kafka.EventStreams;
import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import com.oneday.dispatch.repository.TileDepthCount;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.grid.events.payload.TileQueueDepthEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TileQueueDepthPublisherTest {

    private DispatchQueueRepository queueRepo;
    private DaStatusService daStatusService;
    private RabbitTemplate rabbitTemplate;
    private DispatchProperties props;
    private TileQueueDepthPublisher publisher;

    private final UUID city = UUID.randomUUID();
    private final UUID tile = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        queueRepo = mock(DispatchQueueRepository.class);
        daStatusService = mock(DaStatusService.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        props = new DispatchProperties();
        publisher = new TileQueueDepthPublisher(queueRepo, daStatusService, rabbitTemplate, props);
    }

    @Test
    void noOpOutsideShiftHours() {
        when(daStatusService.loadedDaIds()).thenReturn(Set.of());
        publisher.publish();
        verifyNoInteractions(queueRepo, rabbitTemplate);
    }

    @Test
    void aggregatesQueuedAndInProgressThenPublishesWhenEnabled() {
        props.getEvents().setPublishTileQueueDepth(true);
        when(daStatusService.loadedDaIds()).thenReturn(Set.of(UUID.randomUUID()));
        when(queueRepo.activeDepthByTile(any())).thenReturn(List.of(
                new TileDepthCount(city, tile, TaskStatus.QUEUED, 3),
                new TileDepthCount(city, tile, TaskStatus.IN_PROGRESS, 2)));

        publisher.publish();

        ArgumentCaptor<TileQueueDepthEvent> captor = ArgumentCaptor.forClass(TileQueueDepthEvent.class);
        verify(rabbitTemplate).convertAndSend(eq(EventStreams.TILE_QUEUE_DEPTH), eq("tile.queue.depth"), captor.capture());
        TileQueueDepthEvent e = captor.getValue();
        assertThat(e.tileId()).isEqualTo(tile);
        assertThat(e.cityId()).isEqualTo(city);
        assertThat(e.unservedOrders()).isEqualTo(3);   // QUEUED only
        assertThat(e.bookedOrders()).isEqualTo(5);     // QUEUED + IN_PROGRESS
    }

    @Test
    void suppressesPublishWhenFlagOff() {
        props.getEvents().setPublishTileQueueDepth(false);
        when(daStatusService.loadedDaIds()).thenReturn(Set.of(UUID.randomUUID()));
        when(queueRepo.activeDepthByTile(any())).thenReturn(List.of(
                new TileDepthCount(city, tile, TaskStatus.QUEUED, 1)));

        publisher.publish();

        verify(rabbitTemplate, never()).convertAndSend(any(), any(), any(Object.class));
    }
}
