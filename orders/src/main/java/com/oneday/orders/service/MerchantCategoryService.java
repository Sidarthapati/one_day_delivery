package com.oneday.orders.service;

import com.oneday.orders.dto.MerchantCategoryRequest;
import com.oneday.orders.dto.MerchantCategoryResponse;

import java.util.List;
import java.util.UUID;

/**
 * Per-merchant section categories. All operations are scoped to the B2B account id supplied by the
 * controller — one merchant can never see or mutate another's categories.
 */
public interface MerchantCategoryService {

    List<MerchantCategoryResponse> list(UUID accountId);

    MerchantCategoryResponse create(UUID accountId, MerchantCategoryRequest request);

    MerchantCategoryResponse rename(UUID accountId, UUID categoryId, MerchantCategoryRequest request);

    void delete(UUID accountId, UUID categoryId);

    /** A category id that does not exist for this merchant → mapped to 404. */
    class CategoryNotFoundException extends RuntimeException {
        public CategoryNotFoundException(String message) { super(message); }
    }

    /** A category name already exists for this merchant → mapped to 409. */
    class DuplicateCategoryException extends RuntimeException {
        public DuplicateCategoryException(String message) { super(message); }
    }
}
