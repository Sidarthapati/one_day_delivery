package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.dto.SavedAddressRequest;
import com.oneday.orders.dto.SavedAddressResponse;
import com.oneday.orders.service.AddressBookService;
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

import java.util.List;
import java.util.UUID;

/**
 * Per-user saved address book. Scoped to customer roles (ADMIN manages these directly in
 * the DB); every operation is filtered by the authenticated user id, so addresses are never
 * visible or mutable across users.
 */
@RestController
@RequestMapping("/api/v1/addresses")
class AddressBookController {

    private static final String[] CUSTOMER_ROLES = {"C2C_CUSTOMER", "B2C_CUSTOMER", "B2B_USER"};

    private final AddressBookService addressBookService;

    AddressBookController(AddressBookService addressBookService) {
        this.addressBookService = addressBookService;
    }

    @GetMapping
    public List<SavedAddressResponse> list(@AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireCustomerRole(principal, CUSTOMER_ROLES);
        return addressBookService.list(UUID.fromString(Authz.requireUserId(principal)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SavedAddressResponse create(@AuthenticationPrincipal AuthUserDetails principal,
                                       @Valid @RequestBody SavedAddressRequest request) {
        Authz.requireCustomerRole(principal, CUSTOMER_ROLES);
        return addressBookService.create(UUID.fromString(Authz.requireUserId(principal)), request);
    }

    @PutMapping("/{id}")
    public SavedAddressResponse update(@AuthenticationPrincipal AuthUserDetails principal,
                                       @PathVariable("id") UUID id,
                                       @Valid @RequestBody SavedAddressRequest request) {
        Authz.requireCustomerRole(principal, CUSTOMER_ROLES);
        return addressBookService.update(UUID.fromString(Authz.requireUserId(principal)), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUserDetails principal,
                       @PathVariable("id") UUID id) {
        Authz.requireCustomerRole(principal, CUSTOMER_ROLES);
        addressBookService.delete(UUID.fromString(Authz.requireUserId(principal)), id);
    }
}
