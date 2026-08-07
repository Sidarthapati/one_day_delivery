package com.oneday.orders.service;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.B2bAccount;
import com.oneday.orders.domain.Shipment;

/**
 * Sends customer/merchant notifications for business events, over whichever {@link SmsSender} /
 * {@link EmailSender} is wired (log-sink by default). All methods are best-effort and asynchronous —
 * they never block or fail the caller. This is the single place that decides <em>which</em> events
 * notify and <em>who</em> receives them; the senders only know how to deliver a message.
 */
public interface Notifier {

    /** A shipment reached a customer-meaningful milestone. No-op for states that don't notify. */
    void shipmentMilestone(Shipment shipment, ShipmentState newState);

    /** A COD remittance was paid out to a merchant — confirm to the account's notification emails. */
    void remittancePaid(B2bAccount account, String remittanceNumber, long netPaise, String utr);
}
