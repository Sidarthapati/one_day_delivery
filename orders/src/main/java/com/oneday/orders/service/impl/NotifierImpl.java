package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.B2bAccount;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.service.EmailSender;
import com.oneday.orders.service.Notifier;
import com.oneday.orders.service.SmsSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @see Notifier
 */
@Service
class NotifierImpl implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(NotifierImpl.class);

    /** Customer-meaningful milestones → the phrase used in the message. Other states don't notify. */
    private static final Map<ShipmentState, String> MILESTONE = new EnumMap<>(ShipmentState.class);
    /** Milestones the receiver (consignee) also cares about, not just the booking sender. */
    private static final Set<ShipmentState> ALSO_RECEIVER = Set.of(
            ShipmentState.DROP_ASSIGNED, ShipmentState.DROPPED,
            ShipmentState.HUB_COLLECTED, ShipmentState.DELIVERY_FAILED);

    static {
        MILESTONE.put(ShipmentState.PICKED_UP, "has been picked up");
        MILESTONE.put(ShipmentState.DROP_ASSIGNED, "is out for delivery");
        MILESTONE.put(ShipmentState.DROPPED, "has been delivered");
        MILESTONE.put(ShipmentState.HUB_COLLECTED, "has been collected at the hub");
        MILESTONE.put(ShipmentState.DELIVERY_FAILED, "could not be delivered — we'll retry");
        MILESTONE.put(ShipmentState.RTO_COMPLETED, "has been returned to the origin");
        MILESTONE.put(ShipmentState.CANCELLED, "has been cancelled");
    }

    private final SmsSender sms;
    private final EmailSender email;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "notify-dispatch");
        t.setDaemon(true);
        return t;
    });

    NotifierImpl(SmsSender sms, EmailSender email) {
        this.sms = sms;
        this.email = email;
    }

    @Override
    public void shipmentMilestone(Shipment shipment, ShipmentState newState) {
        String phrase = MILESTONE.get(newState);
        if (phrase == null) {
            return;   // not a notifying milestone
        }
        String ref = shipment.getShipmentRef();
        String msg = "Godspeed: your shipment " + ref + " " + phrase + ".";
        String subject = "Shipment " + ref + " update";

        // Recipients: always the booking sender; the receiver too for delivery-side milestones.
        Set<String> phones = new LinkedHashSet<>();
        Set<String> emails = new LinkedHashSet<>();
        addIf(phones, shipment.getSenderPhone());
        addIf(emails, shipment.getSenderEmail());
        if (ALSO_RECEIVER.contains(newState)) {
            addIf(phones, shipment.getReceiverPhone());
            addIf(emails, shipment.getReceiverEmail());
        }
        dispatch(phones, emails, subject, msg);
    }

    @Override
    public void remittancePaid(B2bAccount account, String remittanceNumber, long netPaise, String utr) {
        String rupees = "₹" + String.format("%,.2f", netPaise / 100.0);
        String subject = "COD remittance " + remittanceNumber + " paid";
        String msg = "Godspeed: your COD remittance " + remittanceNumber + " of " + rupees
                + " has been paid to your bank account (UTR " + utr + ").";

        Set<String> emails = new LinkedHashSet<>();
        addIf(emails, account.getBillingEmail());
        if (account.getCodNotifyEmails() != null) {
            for (String e : account.getCodNotifyEmails().split("[,;\\s]+")) {
                addIf(emails, e);
            }
        }
        dispatch(Set.of(), emails, subject, msg);
    }

    private void dispatch(Set<String> phones, Set<String> emails, String subject, String body) {
        executor.submit(() -> {
            for (String p : phones) {
                safe(() -> sms.send(p, body));
            }
            for (String e : emails) {
                safe(() -> email.send(e, subject, body));
            }
        });
    }

    private void safe(Runnable r) {
        try {
            r.run();
        } catch (Exception ex) {
            log.warn("[notify] send failed: {}", ex.toString());
        }
    }

    private void addIf(Set<String> set, String value) {
        if (value != null && !value.isBlank()) {
            set.add(value.trim());
        }
    }
}
