package com.oneday.orders.service;

import com.oneday.orders.dto.B2bAccountResponse;

import java.util.UUID;

public interface B2bAccountService {

    /** The account owned by this user, or 404 if the user has no B2B account. */
    B2bAccountResponse getMine(UUID ownerUserId);
}
