package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.dto.B2bAccountResponse;
import com.oneday.orders.service.B2bAccountService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/b2b/accounts")
class B2bAccountController {

    private final B2bAccountService accountService;

    B2bAccountController(B2bAccountService accountService) {
        this.accountService = accountService;
    }

    /** The caller's own B2B account status (role-gated to B2B_USER; ADMIN allowed by Authz). */
    @GetMapping("/mine")
    public B2bAccountResponse mine(@AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, "B2B_USER");
        return accountService.getMine(UUID.fromString(Authz.requireUserId(principal)));
    }
}
