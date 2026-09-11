package com.oneday.orders.service.impl;

import com.oneday.orders.domain.RtoAction;
import com.oneday.orders.repository.RtoActionRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RtoWorklistServiceImplTest {

    private RtoActionRepository repo;
    private RtoWorklistServiceImpl service;

    @BeforeEach
    void setUp() {
        repo = mock(RtoActionRepository.class);
        service = new RtoWorklistServiceImpl(repo);
        when(repo.save(any(RtoAction.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void recordCreatesAnOpenItem() {
        when(repo.findByChildRef("REF_R")).thenReturn(Optional.empty());
        service.record("REF", "REF_R", "DEL", "SAME_CITY_FROM_ORIGIN", true);
        verify(repo).save(any(RtoAction.class));
    }

    @Test
    void recordIsIdempotentPerChild() {
        RtoAction existing = new RtoAction();
        when(repo.findByChildRef("REF_R")).thenReturn(Optional.of(existing));
        service.record("REF", "REF_R", "DEL", "SAME_CITY_FROM_ORIGIN", false);
        verify(repo, never()).save(any());
    }

    @Test
    void markDoneRejectsWrongCity() {
        UUID id = UUID.randomUUID();
        RtoAction a = new RtoAction();
        a.setReturnHubCity("DEL");
        a.setStatus("OPEN");
        when(repo.findById(id)).thenReturn(Optional.of(a));

        assertThatThrownBy(() -> service.markDone(id, "BOM", "sm-1"))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(a.getStatus()).isEqualTo("OPEN");
    }

    @Test
    void markDoneClosesInScope() {
        UUID id = UUID.randomUUID();
        RtoAction a = new RtoAction();
        a.setReturnHubCity("DEL");
        a.setStatus("OPEN");
        when(repo.findById(id)).thenReturn(Optional.of(a));

        service.markDone(id, "DEL", "sm-1");
        assertThat(a.getStatus()).isEqualTo("DONE");
        assertThat(a.getDoneBy()).isEqualTo("sm-1");
    }

    @Test
    void closeForChildClosesOpenItem() {
        RtoAction a = new RtoAction();
        a.setStatus("OPEN");
        when(repo.findByChildRef("REF_R")).thenReturn(Optional.of(a));
        service.closeForChild("REF_R", "sorted");
        assertThat(a.getStatus()).isEqualTo("DONE");
    }

    @Test
    void listOpenAllCitiesWhenNullScope() {
        when(repo.findByStatusOrderByCreatedAtDesc("OPEN")).thenReturn(List.of());
        service.listOpen(null);
        verify(repo).findByStatusOrderByCreatedAtDesc("OPEN");
    }
}
