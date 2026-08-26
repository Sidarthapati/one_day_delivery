package com.oneday.orders.service.impl;

import com.oneday.common.port.NotificationPort;
import com.oneday.common.port.dto.NotificationEventType;
import com.oneday.common.port.dto.NotificationRequest;
import com.oneday.orders.config.WalletProperties;
import com.oneday.orders.domain.B2bAccount;
import com.oneday.orders.domain.WalletRechargeOrder;
import com.oneday.orders.dto.WalletResponse;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.repository.WalletRechargeOrderRepository;
import com.oneday.orders.repository.WalletTransactionRepository;
import com.oneday.orders.service.PaymentPort;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the fix for the wallet-recharge amount-manipulation flaw: the credited amount must come
 * from the server-side recharge order, never from the client (Razorpay's signature does not sign
 * the amount, so a client value cannot be trusted).
 */
@ExtendWith(MockitoExtension.class)
class WalletServiceImplTest {

    @Mock private B2bAccountRepository accounts;
    @Mock private WalletTransactionRepository ledger;
    @Mock private WalletRechargeOrderRepository rechargeOrders;
    @Mock private PaymentPort paymentPort;
    @Mock private NotificationPort notificationPort;
    private final WalletProperties walletProperties = new WalletProperties();   // threshold ₹1,000 default

    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final String ORDER_ID = "order_abc";
    private static final String PAYMENT_ID = "pay_abc";
    private static final String SIG = "sig";

    private WalletServiceImpl service() {
        return new WalletServiceImpl(accounts, ledger, rechargeOrders, paymentPort,
                notificationPort, walletProperties);
    }

    @Test
    void debitCrossingBelowThreshold_alertsMerchantOnce() {
        B2bAccount acc = new B2bAccount();
        acc.setWalletBalancePaise(150_000L);        // ₹1,500 — above the ₹1,000 threshold
        acc.setBillingEmail("merchant@acme.example");
        acc.setSupportPhone("+919000000001");

        service().debitForBooking(acc, 60_000L, "1DD-REF", UUID.randomUUID());   // → ₹900, below

        ArgumentCaptor<NotificationRequest> req = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationPort).send(req.capture());
        assertThat(req.getValue().type()).isEqualTo(NotificationEventType.WALLET_LOW);
        assertThat(req.getValue().recipientEmail()).isEqualTo("merchant@acme.example");
        assertThat(req.getValue().params().get("balance")).isEqualTo("900.00");
    }

    @Test
    void debitWhileAlreadyBelowThreshold_doesNotAlertAgain() {
        B2bAccount acc = new B2bAccount();
        acc.setWalletBalancePaise(90_000L);         // ₹900 — already below threshold
        acc.setBillingEmail("merchant@acme.example");

        service().debitForBooking(acc, 10_000L, "1DD-REF", UUID.randomUUID());   // → ₹800, still below

        verify(notificationPort, never()).send(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void confirmRecharge_creditsServerSideOrderAmount() {
        B2bAccount acc = new B2bAccount();
        acc.setWalletBalancePaise(0L);
        when(accounts.findByIdForUpdate(ACCOUNT)).thenReturn(Optional.of(acc));
        when(ledger.existsByReference(PAYMENT_ID)).thenReturn(false);

        WalletRechargeOrder order = new WalletRechargeOrder();
        order.setRazorpayOrderId(ORDER_ID);
        order.setB2bAccountId(ACCOUNT);
        order.setAmountPaise(10_000L); // ₹100 — the amount actually ordered/paid
        when(rechargeOrders.findByRazorpayOrderIdAndB2bAccountId(ORDER_ID, ACCOUNT))
                .thenReturn(Optional.of(order));

        WalletResponse res = service().confirmRecharge(ACCOUNT, ORDER_ID, PAYMENT_ID, SIG);

        // Credited with the stored order amount, and the gateway captured that exact amount.
        assertThat(res.balancePaise()).isEqualTo(10_000L);
        assertThat(acc.getWalletBalancePaise()).isEqualTo(10_000L);
        verify(paymentPort).capture(PAYMENT_ID, 10_000L);
    }

    @Test
    void confirmRecharge_throwsWhenNoServerSideOrder() {
        B2bAccount acc = new B2bAccount();
        acc.setWalletBalancePaise(0L);
        when(accounts.findByIdForUpdate(ACCOUNT)).thenReturn(Optional.of(acc));
        when(ledger.existsByReference(PAYMENT_ID)).thenReturn(false);
        when(rechargeOrders.findByRazorpayOrderIdAndB2bAccountId(ORDER_ID, ACCOUNT))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().confirmRecharge(ACCOUNT, ORDER_ID, PAYMENT_ID, SIG))
                .isInstanceOf(ResponseStatusException.class);

        // No spoofed order → nothing captured, balance untouched.
        verify(paymentPort, never()).capture(anyString(), anyLong());
        assertThat(acc.getWalletBalancePaise()).isEqualTo(0L);
    }

    @Test
    void createRechargeOrder_persistsGatewayOrderedAmount() {
        when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(new B2bAccount()));
        when(paymentPort.createOrder(eq(10_000L), anyString()))
                .thenReturn(new PaymentPort.PaymentOrder(ORDER_ID, 10_000L, "INR", "key_test"));

        service().createRechargeOrder(ACCOUNT, 10_000L);

        ArgumentCaptor<WalletRechargeOrder> captor = ArgumentCaptor.forClass(WalletRechargeOrder.class);
        verify(rechargeOrders).save(captor.capture());
        WalletRechargeOrder saved = captor.getValue();
        assertThat(saved.getRazorpayOrderId()).isEqualTo(ORDER_ID);
        assertThat(saved.getB2bAccountId()).isEqualTo(ACCOUNT);
        assertThat(saved.getAmountPaise()).isEqualTo(10_000L);
    }
}
