package com.oneday.orders.service.impl;

import com.oneday.orders.domain.OrderRefCounter;
import com.oneday.orders.domain.OrderRefCounterId;
import com.oneday.orders.repository.OrderRefCounterRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
class OrderRefServiceImplTest {

    @Mock private OrderRefCounterRepository counterRepository;

    @Test
    void generateRef_formatsAndIncrements() {
        OrderRefServiceImpl service = new OrderRefServiceImpl(counterRepository);
        OrderRefCounter counter = new OrderRefCounter();
        counter.setNextVal(41);
        when(counterRepository.findByIdWithLock(any(OrderRefCounterId.class))).thenReturn(Optional.of(counter));

        String ref = service.generateRef("blr");   // lower-case in → upper-case out

        String today = LocalDate.now(ZoneId.of("Asia/Kolkata")).format(DateTimeFormatter.BASIC_ISO_DATE);
        assertThat(ref).isEqualTo("1DD-ORD-BLR-" + today + "-00042");
        assertThat(counter.getNextVal()).isEqualTo(42);
        verify(counterRepository).insertIfAbsent(eq("BLR"), any(LocalDate.class));
        verify(counterRepository).save(counter);
    }
}
