package com.oneday.orders.service.impl;

import com.oneday.orders.domain.MerchantCategory;
import com.oneday.orders.dto.MerchantCategoryRequest;
import com.oneday.orders.dto.MerchantCategoryResponse;
import com.oneday.orders.repository.MerchantCategoryRepository;
import com.oneday.orders.service.MerchantCategoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
class MerchantCategoryServiceImpl implements MerchantCategoryService {

    private final MerchantCategoryRepository repository;

    MerchantCategoryServiceImpl(MerchantCategoryRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MerchantCategoryResponse> list(UUID accountId) {
        return repository.findByB2bAccountIdAndArchivedAtIsNullOrderByName(accountId).stream()
                .map(MerchantCategoryResponse::from)
                .toList();
    }

    @Override
    @Transactional
    public MerchantCategoryResponse create(UUID accountId, MerchantCategoryRequest request) {
        String name = request.getName().trim();
        requireUnique(accountId, name);
        MerchantCategory entity = new MerchantCategory();
        entity.setB2bAccountId(accountId);
        entity.setName(name);
        return MerchantCategoryResponse.from(repository.save(entity));
    }

    @Override
    @Transactional
    public MerchantCategoryResponse rename(UUID accountId, UUID categoryId, MerchantCategoryRequest request) {
        MerchantCategory entity = repository.findByIdAndB2bAccountIdAndArchivedAtIsNull(categoryId, accountId)
                .orElseThrow(() -> new CategoryNotFoundException("Category not found: " + categoryId));
        String name = request.getName().trim();
        if (!name.equalsIgnoreCase(entity.getName())) {
            requireUnique(accountId, name);
        }
        entity.setName(name);
        return MerchantCategoryResponse.from(repository.save(entity));
    }

    @Override
    @Transactional
    public void delete(UUID accountId, UUID categoryId) {
        MerchantCategory entity = repository.findByIdAndB2bAccountIdAndArchivedAtIsNull(categoryId, accountId)
                .orElseThrow(() -> new CategoryNotFoundException("Category not found: " + categoryId));
        // Soft-delete: existing shipments keep their category_id, and the archived row stays so reports
        // still resolve the name. It just drops out of the pick list. No cascade, no history lost.
        entity.setArchivedAt(Instant.now());
        repository.save(entity);
    }

    private void requireUnique(UUID accountId, String name) {
        if (repository.existsByB2bAccountIdAndNameIgnoreCaseAndArchivedAtIsNull(accountId, name)) {
            throw new DuplicateCategoryException("Category already exists: " + name);
        }
    }
}
