package com.oneday.orders.repository;

import com.oneday.orders.domain.B2bAccount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("B2bAccountRepository")
class B2bAccountRepositoryTest extends AbstractRepositoryTest {

    @Autowired private B2bAccountRepository repo;
    @Autowired private PlatformTransactionManager txManager;

    @Test
    void saveAndFindById_roundTrip() {
        B2bAccount saved = repo.save(TestFixtures.b2bAccount("billing@test-acme.com", "DEL"));

        assertThat(repo.findById(saved.getId())).isPresent();
        assertThat(saved.getAccountName()).isEqualTo("Acme Corp");
        assertThat(saved.getIsActive()).isTrue();
    }

    @Test
    void findByCityId_returnsAccountsInThatCity() {
        repo.save(TestFixtures.b2bAccount("del1@test.com", "DEL"));
        repo.save(TestFixtures.b2bAccount("del2@test.com", "DEL"));
        repo.save(TestFixtures.b2bAccount("bom1@test.com", "BOM"));

        List<B2bAccount> delhiAccounts = repo.findByCityId("DEL");

        assertThat(delhiAccounts)
                .extracting(B2bAccount::getBillingEmail)
                .contains("del1@test.com", "del2@test.com")
                .doesNotContain("bom1@test.com");
    }

    @Test
    void findByIsActive_separatesActiveFromInactive() {
        B2bAccount active = TestFixtures.b2bAccount("active@test.com", "DEL");
        B2bAccount inactive = TestFixtures.b2bAccount("inactive@test.com", "DEL");
        inactive.setIsActive(false);

        repo.save(active);
        repo.save(inactive);

        List<B2bAccount> activeAccounts = repo.findByIsActive(true);
        List<B2bAccount> inactiveAccounts = repo.findByIsActive(false);

        assertThat(activeAccounts).extracting(B2bAccount::getBillingEmail)
                .contains("active@test.com");
        assertThat(inactiveAccounts).extracting(B2bAccount::getBillingEmail)
                .contains("inactive@test.com");
    }

    @Test
    void findByBillingEmail_returnsMatchingAccount() {
        repo.save(TestFixtures.b2bAccount("unique@test-corp.com", "DEL"));

        assertThat(repo.findByBillingEmail("unique@test-corp.com")).isPresent();
        assertThat(repo.findByBillingEmail("other@test.com")).isEmpty();
    }

    // ── SELECT FOR UPDATE: concurrent credit check ─────────────────────────
    // Runs outside the @DataJpaTest rollback transaction so that each thread's
    // committed changes are visible to the other thread via PostgreSQL MVCC.

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void findByIdForUpdate_concurrentBooking_onlyOneSucceeds() throws Exception {
        long creditLimit   = 1_000_000L;
        long existing      =   500_000L;  // room for exactly one 500_000-paise booking
        long bookingAmount =   500_000L;

        TransactionTemplate template = new TransactionTemplate(txManager);

        // Insert account in its own committed transaction so both threads can see it
        B2bAccount created = template.execute(s -> {
            B2bAccount acc = TestFixtures.b2bAccount("concurrency@test.com", "BLR");
            acc.setCreditLimitPaise(creditLimit);
            acc.setOutstandingBalancePaise(existing);
            return repo.save(acc);
        });
        UUID accountId = created.getId();

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures  = new AtomicInteger(0);
        CountDownLatch ready    = new CountDownLatch(2);
        CountDownLatch go       = new CountDownLatch(1);

        Runnable task = () -> {
            ready.countDown();
            try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            try {
                template.execute(status -> {
                    B2bAccount locked = repo.findByIdForUpdate(accountId).orElseThrow();
                    long newOutstanding = locked.getOutstandingBalancePaise() + bookingAmount;
                    if (newOutstanding > locked.getCreditLimitPaise()) {
                        // Mark rollback and abort via RuntimeException
                        status.setRollbackOnly();
                        throw new RuntimeException("credit limit exceeded");
                    }
                    locked.setOutstandingBalancePaise(newOutstanding);
                    repo.save(locked);
                    return null;
                });
                successes.incrementAndGet();
            } catch (Exception e) {
                failures.incrementAndGet();
            }
        };

        ExecutorService exec = Executors.newFixedThreadPool(2);
        exec.submit(task);
        exec.submit(task);
        ready.await();
        go.countDown();
        exec.shutdown();
        exec.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(successes.get()).isEqualTo(1);
        assertThat(failures.get()).isEqualTo(1);

        // The committed winner consumed all remaining credit
        Long finalBalance = template.execute(s ->
                repo.findById(accountId).orElseThrow().getOutstandingBalancePaise());
        assertThat(finalBalance).isEqualTo(creditLimit);

        // Cleanup — test runs outside rollback scope so we must delete explicitly
        template.execute(s -> { repo.deleteById(accountId); return null; });
    }
}
