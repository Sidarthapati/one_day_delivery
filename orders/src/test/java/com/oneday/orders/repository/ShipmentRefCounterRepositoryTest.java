package com.oneday.orders.repository;

import com.oneday.orders.domain.ShipmentRefCounter;
import com.oneday.orders.domain.ShipmentRefCounterId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ShipmentRefCounterRepository")
class ShipmentRefCounterRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    private ShipmentRefCounterRepository repo;

    @Test
    void saveAndFindById_compositeKeyRoundTrip() {
        LocalDate today = LocalDate.of(2026, 5, 27);
        repo.save(TestFixtures.refCounter("DEL", today, 1));

        Optional<ShipmentRefCounter> found = repo.findById(new ShipmentRefCounterId("DEL", today));

        assertThat(found).isPresent();
        assertThat(found.get().getNextVal()).isEqualTo(1);
    }

    @Test
    void increment_incrementsNextValByOne() {
        LocalDate today = LocalDate.of(2026, 5, 27);
        ShipmentRefCounterId id = new ShipmentRefCounterId("BOM", today);
        repo.save(TestFixtures.refCounter("BOM", today, 5));

        int updated = repo.increment(id);

        assertThat(updated).isEqualTo(1);
        ShipmentRefCounter counter = repo.findById(id).orElseThrow();
        assertThat(counter.getNextVal()).isEqualTo(6);
    }

    @Test
    void increment_isIsolatedByCityAndDate() {
        LocalDate d1 = LocalDate.of(2026, 5, 27);
        LocalDate d2 = LocalDate.of(2026, 5, 28);
        repo.save(TestFixtures.refCounter("DEL", d1, 10));
        repo.save(TestFixtures.refCounter("DEL", d2, 20));

        repo.increment(new ShipmentRefCounterId("DEL", d1));

        assertThat(repo.findById(new ShipmentRefCounterId("DEL", d1)).orElseThrow().getNextVal()).isEqualTo(11);
        assertThat(repo.findById(new ShipmentRefCounterId("DEL", d2)).orElseThrow().getNextVal()).isEqualTo(20);
    }
}
