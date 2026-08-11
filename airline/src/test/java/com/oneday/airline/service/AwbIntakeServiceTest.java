package com.oneday.airline.service;

import com.oneday.airline.domain.Awb;
import com.oneday.airline.domain.AwbStatus;
import com.oneday.airline.repository.AwbRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AwbIntakeServiceTest {

    private final AwbRepository awbRepository = mock(AwbRepository.class);
    private final AwbIntakeService service = new AwbIntakeService(awbRepository);
    private final LocalDate date = LocalDate.of(2026, 7, 20);

    private Awb awb(AwbStatus status) {
        Awb a = new Awb();
        a.setStatus(status);
        a.setAwbNo("AWB-ODDELBOM06-2026-07-20-placeholder");
        return a;
    }

    @Test
    void stampsRealAwbOnEveryBookedBagButNotSupersededOnes() {
        Awb booked1 = awb(AwbStatus.BOOKED);
        Awb booked2 = awb(AwbStatus.BOOKED);
        Awb superseded = awb(AwbStatus.SUPERSEDED);
        when(awbRepository.findByFlightNoAndFlightDate("AI806", date))
                .thenReturn(List.of(booked1, booked2, superseded));

        int updated = service.assignRealAwb("AI806", date, "098-12345675");

        assertThat(updated).isEqualTo(2);
        assertThat(booked1.getAwbNo()).isEqualTo("098-12345675");
        assertThat(booked2.getAwbNo()).isEqualTo("098-12345675");
        assertThat(superseded.getAwbNo()).isEqualTo("AWB-ODDELBOM06-2026-07-20-placeholder");
        verify(awbRepository).saveAll(List.of(booked1, booked2));
    }

    @Test
    void returnsZeroWhenTheFlightHasNoBookedAwb() {
        when(awbRepository.findByFlightNoAndFlightDate("AI806", date)).thenReturn(List.of());

        assertThat(service.assignRealAwb("AI806", date, "098-12345675")).isZero();
    }
}
