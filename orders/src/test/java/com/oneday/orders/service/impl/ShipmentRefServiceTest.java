package com.oneday.orders.service.impl;

import com.oneday.orders.domain.ShipmentRefCounter;
import com.oneday.orders.domain.ShipmentRefCounterId;
import com.oneday.orders.repository.ShipmentRefCounterRepository;
import com.oneday.orders.service.ShipmentRefService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShipmentRefServiceTest {

    @Mock
    private ShipmentRefCounterRepository counterRepository;

    private ShipmentRefService service;

    @BeforeEach
    void setUp() {
        service = new ShipmentRefServiceImpl(counterRepository);
    }

    // -------------------------------------------------------------------------
    // Format tests
    // -------------------------------------------------------------------------

    @Test
    void generateRef_noExistingCounter_startsAt00001() {
        // insertIfAbsent creates the row (void, no stub needed); findByIdWithLock
        // returns it with nextVal=0 as if freshly inserted.
        when(counterRepository.findByIdWithLock(any()))
                .thenReturn(Optional.of(counterWithNextVal("BLR", 0)));
        when(counterRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String ref = service.generateRef("BLR");

        String today = LocalDate.now(ZoneId.of("Asia/Kolkata"))
                .format(DateTimeFormatter.BASIC_ISO_DATE);
        assertThat(ref).isEqualTo("1DD-BLR-" + today + "-00001");
    }

    @Test
    void generateRef_existingCounter_incrementsByOne() {
        ShipmentRefCounter existing = counterWithNextVal("BLR", 41);
        when(counterRepository.findByIdWithLock(any())).thenReturn(Optional.of(existing));
        when(counterRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String ref = service.generateRef("BLR");

        String today = LocalDate.now(ZoneId.of("Asia/Kolkata"))
                .format(DateTimeFormatter.BASIC_ISO_DATE);
        assertThat(ref).isEqualTo("1DD-BLR-" + today + "-00042");
    }

    @Test
    void generateRef_cityCodeIsUppercased() {
        when(counterRepository.findByIdWithLock(any()))
                .thenReturn(Optional.of(counterWithNextVal("BLR", 0)));
        when(counterRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String ref = service.generateRef("blr");

        assertThat(ref).startsWith("1DD-BLR-");
    }

    @Test
    void generateRef_sequenceZeroPaddedToFiveDigits() {
        ShipmentRefCounter existing = counterWithNextVal("DEL", 99998);
        when(counterRepository.findByIdWithLock(any())).thenReturn(Optional.of(existing));
        when(counterRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String ref = service.generateRef("DEL");

        assertThat(ref).endsWith("-99999");
    }

    // -------------------------------------------------------------------------
    // Persistence tests
    // -------------------------------------------------------------------------

    @Test
    void generateRef_savesUpdatedCounter() {
        ShipmentRefCounter existing = counterWithNextVal("BOM", 10);
        when(counterRepository.findByIdWithLock(any())).thenReturn(Optional.of(existing));
        when(counterRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.generateRef("BOM");

        ArgumentCaptor<ShipmentRefCounter> captor = ArgumentCaptor.forClass(ShipmentRefCounter.class);
        verify(counterRepository).save(captor.capture());
        assertThat(captor.getValue().getNextVal()).isEqualTo(11);
    }

    @Test
    void generateRef_noExistingCounter_savesCounterWithCorrectId() {
        // insertIfAbsent creates the row; findByIdWithLock returns it with nextVal=0.
        when(counterRepository.findByIdWithLock(any()))
                .thenReturn(Optional.of(counterWithNextVal("MAA", 0)));
        when(counterRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.generateRef("MAA");

        ArgumentCaptor<ShipmentRefCounter> captor = ArgumentCaptor.forClass(ShipmentRefCounter.class);
        verify(counterRepository).save(captor.capture());
        ShipmentRefCounter saved = captor.getValue();
        assertThat(saved.getId().getCityCode()).isEqualTo("MAA");
        assertThat(saved.getId().getDateKey()).isEqualTo(LocalDate.now(ZoneId.of("Asia/Kolkata")));
        assertThat(saved.getNextVal()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ShipmentRefCounter counterWithNextVal(String cityCode, int nextVal) {
        ShipmentRefCounter c = new ShipmentRefCounter();
        c.setId(new ShipmentRefCounterId(cityCode, LocalDate.now(ZoneId.of("Asia/Kolkata"))));
        c.setNextVal(nextVal);
        return c;
    }
}
