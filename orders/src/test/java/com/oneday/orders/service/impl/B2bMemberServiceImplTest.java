package com.oneday.orders.service.impl;

import com.oneday.auth.dto.response.UserResponse;
import com.oneday.auth.exception.UserNotFoundException;
import com.oneday.auth.service.UserService;
import com.oneday.orders.domain.B2bAccountMember;
import com.oneday.orders.domain.MemberRole;
import com.oneday.orders.dto.MemberResponse;
import com.oneday.orders.repository.B2bAccountMemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class B2bMemberServiceImplTest {

    @Mock private B2bAccountMemberRepository members;
    @Mock private UserService userService;

    private final UUID account = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();

    private B2bMemberServiceImpl service() {
        return new B2bMemberServiceImpl(members, userService);
    }

    private void callerIsOwner() {
        B2bAccountMember m = new B2bAccountMember();
        m.setRole(MemberRole.OWNER);
        when(members.findByB2bAccountIdAndUserId(account, owner)).thenReturn(Optional.of(m));
    }

    private static UserResponse user(UUID id, String email, String role) {
        return new UserResponse(id, email, "Teammate", role, null, true);
    }

    @Test
    void ownerAddsAnExistingBusinessUser_asMember() {
        callerIsOwner();
        UUID newUser = UUID.randomUUID();
        when(userService.getUserByEmail("teammate@acme.example")).thenReturn(user(newUser, "teammate@acme.example", "B2B_USER"));
        when(members.existsByUserId(newUser)).thenReturn(false);
        when(members.save(any(B2bAccountMember.class))).thenAnswer(i -> i.getArgument(0));

        MemberResponse r = service().add(account, owner, "teammate@acme.example");

        assertThat(r.role()).isEqualTo("MEMBER");
        ArgumentCaptor<B2bAccountMember> saved = ArgumentCaptor.forClass(B2bAccountMember.class);
        verify(members).save(saved.capture());
        assertThat(saved.getValue().getB2bAccountId()).isEqualTo(account);
        assertThat(saved.getValue().getUserId()).isEqualTo(newUser);
        assertThat(saved.getValue().getRole()).isEqualTo(MemberRole.MEMBER);
    }

    @Test
    void nonOwnerCannotAdd() {
        B2bAccountMember plain = new B2bAccountMember();
        plain.setRole(MemberRole.MEMBER);
        UUID caller = UUID.randomUUID();
        when(members.findByB2bAccountIdAndUserId(account, caller)).thenReturn(Optional.of(plain));

        assertThatThrownBy(() -> service().add(account, caller, "x@y.com"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("owner");
        verify(members, never()).save(any());
    }

    @Test
    void unknownEmailIs404() {
        callerIsOwner();
        when(userService.getUserByEmail("nobody@x.com")).thenThrow(new UserNotFoundException("nope"));
        assertThatThrownBy(() -> service().add(account, owner, "nobody@x.com"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("sign up");
        verify(members, never()).save(any());
    }

    @Test
    void nonBusinessUserRejected() {
        callerIsOwner();
        when(userService.getUserByEmail("cust@x.com")).thenReturn(user(UUID.randomUUID(), "cust@x.com", "B2C_CUSTOMER"));
        assertThatThrownBy(() -> service().add(account, owner, "cust@x.com"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("business user");
        verify(members, never()).save(any());
    }

    @Test
    void alreadyOnAnAccountIsConflict() {
        callerIsOwner();
        UUID u = UUID.randomUUID();
        when(userService.getUserByEmail("dup@x.com")).thenReturn(user(u, "dup@x.com", "B2B_USER"));
        when(members.existsByUserId(u)).thenReturn(true);
        assertThatThrownBy(() -> service().add(account, owner, "dup@x.com"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already belongs");
        verify(members, never()).save(any());
    }

    @Test
    void ownerCannotBeRemoved() {
        callerIsOwner();
        B2bAccountMember target = new B2bAccountMember();
        target.setRole(MemberRole.OWNER);
        UUID ownerTarget = UUID.randomUUID();
        when(members.findByB2bAccountIdAndUserId(account, ownerTarget)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service().remove(account, owner, ownerTarget))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("owner can't be removed");
        verify(members, never()).delete(any());
    }

    @Test
    void ownerRemovesAMember() {
        callerIsOwner();
        B2bAccountMember target = new B2bAccountMember();
        target.setRole(MemberRole.MEMBER);
        UUID memberId = UUID.randomUUID();
        when(members.findByB2bAccountIdAndUserId(account, memberId)).thenReturn(Optional.of(target));

        service().remove(account, owner, memberId);

        verify(members).delete(target);
    }
}
