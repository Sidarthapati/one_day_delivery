package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.dto.MerchantCategoryRequest;
import com.oneday.orders.dto.MerchantCategoryResponse;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.service.MerchantCategoryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * A merchant's own section categories. Scoped to the caller's B2B account (resolved from the
 * principal, never a param), so categories are never visible or mutable across merchants.
 */
@RestController
@RequestMapping("/api/v1/categories")
class MerchantCategoryController {

    private final MerchantCategoryService categoryService;
    private final B2bAccountRepository accounts;

    MerchantCategoryController(MerchantCategoryService categoryService, B2bAccountRepository accounts) {
        this.categoryService = categoryService;
        this.accounts = accounts;
    }

    @GetMapping
    public List<MerchantCategoryResponse> list(@AuthenticationPrincipal AuthUserDetails principal) {
        return categoryService.list(ownedAccountId(principal));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MerchantCategoryResponse create(@AuthenticationPrincipal AuthUserDetails principal,
                                           @Valid @RequestBody MerchantCategoryRequest request) {
        return categoryService.create(ownedAccountId(principal), request);
    }

    @PutMapping("/{id}")
    public MerchantCategoryResponse rename(@AuthenticationPrincipal AuthUserDetails principal,
                                           @PathVariable("id") UUID id,
                                           @Valid @RequestBody MerchantCategoryRequest request) {
        return categoryService.rename(ownedAccountId(principal), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUserDetails principal, @PathVariable("id") UUID id) {
        categoryService.delete(ownedAccountId(principal), id);
    }

    /** The B2B account owned by the caller, or 404 (also gates the endpoint to B2B users). */
    private UUID ownedAccountId(AuthUserDetails principal) {
        Authz.requireRole(principal, "B2B_USER");
        UUID userId = UUID.fromString(Authz.requireUserId(principal));
        return accounts.findByOwnerUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No B2B account for this user"))
                .getId();
    }
}
