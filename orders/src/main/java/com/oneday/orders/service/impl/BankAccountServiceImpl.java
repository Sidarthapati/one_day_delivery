package com.oneday.orders.service.impl;

import com.oneday.orders.domain.B2bAccount;
import com.oneday.orders.domain.BankVerificationState;
import com.oneday.orders.dto.BankAccountRequest;
import com.oneday.orders.dto.BankAccountResponse;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.service.BankAccountService;
import com.oneday.orders.service.PayoutPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@Service
class BankAccountServiceImpl implements BankAccountService {

    private static final Logger log = LoggerFactory.getLogger(BankAccountServiceImpl.class);

    private final B2bAccountRepository accounts;
    private final PayoutPort payouts;

    BankAccountServiceImpl(B2bAccountRepository accounts, PayoutPort payouts) {
        this.accounts = accounts;
        this.payouts = payouts;
    }

    @Override
    @Transactional(readOnly = true)
    public BankAccountResponse get(UUID accountId) {
        return accounts.findById(accountId).map(BankAccountServiceImpl::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
    }

    @Override
    @Transactional
    public BankAccountResponse submit(UUID accountId, BankAccountRequest req) {
        B2bAccount a = accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));

        String acct = req.accountNumber().replaceAll("\\s", "");
        String ifsc = req.ifsc().toUpperCase();

        // Kick off verification (penny-drop, or a manual mark) before we trust the details.
        // Match the penny-drop's bank-registered name against the legal/business name we hold.
        PayoutPort.VerificationOutcome outcome = payouts.verifyBankAccount(
                new PayoutPort.BankAccount(acct, ifsc, req.beneficiaryName()),
                a.getAccountName());

        a.setBankAccountNumber(acct);
        a.setBankAccountMasked(mask(acct));
        a.setBankIfsc(ifsc);
        a.setBankBeneficiaryName(req.beneficiaryName());
        a.setBankName(req.bankName());
        a.setCodNotifyEmails(req.notifyEmails());
        a.setBankVerificationState(outcome.state());
        a.setBankPennyDropRef(outcome.reference());
        a.setBankVerified(outcome.state().isPayable());
        a.setBankVerifiedAt(outcome.state().isPayable() ? Instant.now() : null);
        accounts.save(a);

        log.info("Bank account for {} submitted → {} ({})", accountId, outcome.state(), outcome.message());
        return toResponse(a);
    }

    private static BankAccountResponse toResponse(B2bAccount a) {
        BankVerificationState state = a.getBankVerificationState() == null
                ? BankVerificationState.NONE : a.getBankVerificationState();
        if (a.getBankAccountMasked() == null && state == BankVerificationState.NONE) {
            return BankAccountResponse.none();
        }
        return new BankAccountResponse(
                a.getBankAccountMasked() != null,
                a.getBankAccountMasked(),
                a.getBankIfsc(),
                a.getBankBeneficiaryName(),
                a.getBankName(),
                state.name(),
                state.isPayable(),
                a.getBankVerifiedAt(),
                a.getCodNotifyEmails());
    }

    private static String mask(String acct) {
        int keep = 4;
        if (acct.length() <= keep) return acct;
        return "X".repeat(acct.length() - keep) + acct.substring(acct.length() - keep);
    }
}
