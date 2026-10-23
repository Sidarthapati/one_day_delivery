package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.B2bAccount;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.service.EmailSender;
import com.oneday.orders.service.SmsSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotifierImplTest {

    @Mock SmsSender sms;
    @Mock EmailSender email;
    @InjectMocks NotifierImpl notifier;

    private static Shipment shipment() {
        Shipment s = new Shipment();
        s.setShipmentRef("REF");
        s.setSenderPhone("+91900000001");
        s.setSenderEmail("sender@x.com");
        s.setReceiverPhone("+91900000002");
        s.setReceiverEmail("receiver@x.com");
        return s;
    }

    @Test
    void nonMilestoneState_doesNothing() {
        notifier.shipmentMilestone(shipment(), ShipmentState.BOOKED);
        verify(sms, never()).send(any(), any());
    }

    @Test
    void senderOnlyMilestone_notifiesSender() {
        notifier.shipmentMilestone(shipment(), ShipmentState.PICKED_UP);
        verify(sms, timeout(2000)).send(eq("+91900000001"), anyString());
        verify(email, timeout(2000)).send(eq("sender@x.com"), anyString(), anyString());
        // receiver is NOT notified for a pickup milestone
        verify(sms, never()).send(eq("+91900000002"), anyString());
    }

    @Test
    void deliveryMilestone_notifiesSenderAndReceiver() {
        notifier.shipmentMilestone(shipment(), ShipmentState.DROPPED);
        verify(sms, timeout(2000)).send(eq("+91900000002"), anyString()); // receiver too
        verify(email, timeout(2000)).send(eq("receiver@x.com"), anyString(), anyString());
    }

    @Test
    void blankRecipients_skipped() {
        Shipment s = new Shipment();
        s.setShipmentRef("REF");
        s.setSenderPhone("  ");   // blank → skipped
        s.setSenderEmail(null);   // null → skipped
        notifier.shipmentMilestone(s, ShipmentState.PICKED_UP);
        verify(sms, never()).send(any(), any());
    }

    @Test
    void smsFailure_isSwallowed_emailStillSent() {
        doThrow(new RuntimeException("sms down")).when(sms).send(anyString(), anyString());
        notifier.shipmentMilestone(shipment(), ShipmentState.PICKED_UP);
        verify(email, timeout(2000)).send(eq("sender@x.com"), anyString(), anyString());
    }

    @Test
    void remittancePaid_emailsBillingAndNotifyList() {
        B2bAccount a = new B2bAccount();
        a.setBillingEmail("billing@acme.com");
        a.setCodNotifyEmails("ops@acme.com, finance@acme.com");
        notifier.remittancePaid(a, "RMT/1", 95000L, "UTR1");
        verify(email, timeout(2000)).send(eq("billing@acme.com"), anyString(), anyString());
        verify(email, timeout(2000)).send(eq("ops@acme.com"), anyString(), anyString());
        verify(email, timeout(2000)).send(eq("finance@acme.com"), anyString(), anyString());
    }

    @Test
    void remittancePaid_nullNotifyList_onlyBilling() {
        B2bAccount a = new B2bAccount();
        a.setBillingEmail("billing@acme.com"); // codNotifyEmails null
        notifier.remittancePaid(a, "RMT/2", 100L, "UTR2");
        verify(email, timeout(2000)).send(eq("billing@acme.com"), anyString(), anyString());
    }
}
