package com.oneday.orders.repository;

import com.oneday.orders.domain.B2bAccount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("B2bAccountRepository")
class B2bAccountRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    private B2bAccountRepository repo;

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
}
