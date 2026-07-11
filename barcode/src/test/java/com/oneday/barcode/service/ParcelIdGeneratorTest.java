package com.oneday.barcode.service;

import com.oneday.barcode.domain.ParcelIdCounter;
import com.oneday.barcode.repository.ParcelIdCounterRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ParcelIdGeneratorTest {

    @Mock ParcelIdCounterRepository counters;
    @InjectMocks ParcelIdGenerator generator;

    @Test
    void mintsFormattedBarcode_andBumpsCounter() {
        ParcelIdCounter counter = new ParcelIdCounter();
        counter.setNextSeq(42);
        when(counters.findByIdWithLock(any())).thenReturn(Optional.of(counter));

        String barcode = generator.next("DEL");

        String today = LocalDate.now(ZoneId.of("Asia/Kolkata")).format(DateTimeFormatter.ofPattern("yyMMdd"));
        assertThat(barcode).isEqualTo("1DD-DEL-" + today + "-000042"); // 6-digit zero-padded seq
        assertThat(counter.getNextSeq()).isEqualTo(43);                // counter advanced
        verify(counters).insertIfAbsent(eq("DEL"), any());             // row materialised first
    }
}
