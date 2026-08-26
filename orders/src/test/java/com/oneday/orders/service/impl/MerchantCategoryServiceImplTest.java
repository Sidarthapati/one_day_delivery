package com.oneday.orders.service.impl;

import com.oneday.orders.domain.MerchantCategory;
import com.oneday.orders.dto.MerchantCategoryRequest;
import com.oneday.orders.dto.MerchantCategoryResponse;
import com.oneday.orders.repository.MerchantCategoryRepository;
import com.oneday.orders.service.MerchantCategoryService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Categories are per-merchant and must stay that way: create must reject duplicate names, and
 * rename/delete must look the row up scoped to the caller's account so one merchant can never touch
 * another's category by id. These pin those guarantees.
 */
class MerchantCategoryServiceImplTest {

    private final MerchantCategoryRepository repo = mock(MerchantCategoryRepository.class);
    private final MerchantCategoryServiceImpl service = new MerchantCategoryServiceImpl(repo);

    private static final UUID ACCOUNT = UUID.randomUUID();

    private static MerchantCategoryRequest req(String name) {
        MerchantCategoryRequest r = new MerchantCategoryRequest();
        r.setName(name);
        return r;
    }

    @Test
    void createTrimsNameAndStampsTheCallersAccount() {
        when(repo.existsByB2bAccountIdAndNameIgnoreCase(ACCOUNT, "Electronics")).thenReturn(false);
        when(repo.save(any(MerchantCategory.class))).thenAnswer(inv -> inv.getArgument(0));

        MerchantCategoryResponse resp = service.create(ACCOUNT, req("  Electronics  "));

        assertThat(resp.name()).isEqualTo("Electronics");
        var saved = forClass(MerchantCategory.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getB2bAccountId()).isEqualTo(ACCOUNT);
        assertThat(saved.getValue().getName()).isEqualTo("Electronics");
    }

    @Test
    void createRejectsADuplicateName() {
        when(repo.existsByB2bAccountIdAndNameIgnoreCase(ACCOUNT, "Apparel")).thenReturn(true);

        assertThatThrownBy(() -> service.create(ACCOUNT, req("Apparel")))
                .isInstanceOf(MerchantCategoryService.DuplicateCategoryException.class);
        verify(repo, never()).save(any());
    }

    @Test
    void renameOfAnotherMerchantsCategoryIsNotFound() {
        UUID foreignId = UUID.randomUUID();
        when(repo.findByIdAndB2bAccountId(foreignId, ACCOUNT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rename(ACCOUNT, foreignId, req("Hijack")))
                .isInstanceOf(MerchantCategoryService.CategoryNotFoundException.class);
    }

    @Test
    void deleteIsScopedToTheCallersAccount() {
        UUID id = UUID.randomUUID();
        when(repo.findByIdAndB2bAccountId(id, ACCOUNT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(ACCOUNT, id))
                .isInstanceOf(MerchantCategoryService.CategoryNotFoundException.class);
        // Looked up strictly within the caller's account — never a bare findById.
        verify(repo).findByIdAndB2bAccountId(eq(id), eq(ACCOUNT));
        verify(repo, never()).delete(any());
    }
}
