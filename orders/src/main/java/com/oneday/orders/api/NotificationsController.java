package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.domain.B2bAccount;
import com.oneday.orders.dto.NotificationView;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.repository.NotificationLogRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * The merchant's in-app notifications (the header bell). Returns the recent notifications addressed to
 * the caller's B2B account — wallet-low today, and every future alert built on the notification
 * foundation. Scoped to the caller's own account contacts, never a param.
 */
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationsController {

    private final NotificationLogRepository notifications;
    private final B2bAccountRepository accounts;

    NotificationsController(NotificationLogRepository notifications, B2bAccountRepository accounts) {
        this.notifications = notifications;
        this.accounts = accounts;
    }

    /** The caller's recent notifications, newest first. Scoped by account id (not recipient string). */
    @GetMapping("/mine")
    public List<NotificationView> mine(@AuthenticationPrincipal AuthUserDetails principal) {
        B2bAccount account = ownedAccount(principal);
        return notifications.findTop50ByB2bAccountIdOrderByCreatedAtDesc(account.getId()).stream()
                .map(NotificationView::from)
                .toList();
    }

    /** The B2B account owned by the caller, or 404 (also gates the endpoint to B2B users). */
    private B2bAccount ownedAccount(AuthUserDetails principal) {
        Authz.requireRole(principal, "B2B_USER");
        UUID userId = UUID.fromString(Authz.requireUserId(principal));
        return accounts.findByMemberUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No B2B account for this user"));
    }
}
