package com.oneday.orders.service;

import com.oneday.orders.dto.SavedAddressRequest;
import com.oneday.orders.dto.SavedAddressResponse;

import java.util.List;
import java.util.UUID;

/**
 * Per-user saved address book. All operations are scoped to the authenticated user id
 * supplied by the controller — one user can never see or mutate another's addresses.
 */
public interface AddressBookService {

    List<SavedAddressResponse> list(UUID userId);

    SavedAddressResponse create(UUID userId, SavedAddressRequest request);

    SavedAddressResponse update(UUID userId, UUID addressId, SavedAddressRequest request);

    void delete(UUID userId, UUID addressId);

    /** Thrown when an address id does not exist for this user → mapped to 404. */
    class AddressNotFoundException extends RuntimeException {
        public AddressNotFoundException(String message) { super(message); }
    }
}
