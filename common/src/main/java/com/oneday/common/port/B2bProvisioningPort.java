package com.oneday.common.port;

import com.oneday.common.port.dto.B2bProvisioningRequest;

import java.util.UUID;

/**
 * Implemented by M4 (orders). Called by M1 (auth) when an ADMIN approves a business onboarding:
 * auth owns identity (the user + role), orders owns the {@code B2bAccount} (the business entity,
 * credit, KYC state). This keeps the module boundary — auth compiles only against this interface.
 *
 * <p>Idempotent on {@code ownerUserId}: a second call for the same owner returns the existing account.</p>
 */
public interface B2bProvisioningPort {

    /** Provision (or return the existing) B2B account for the owner. Returns the account id. */
    UUID provisionAccount(B2bProvisioningRequest request);
}
