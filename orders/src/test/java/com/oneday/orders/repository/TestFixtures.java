package com.oneday.orders.repository;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.DropType;
import com.oneday.common.domain.enums.PickupType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.Address;
import com.oneday.orders.domain.B2bAccount;
import com.oneday.orders.domain.IdempotencyKey;
import com.oneday.orders.domain.IdempotencyKeyId;
import com.oneday.orders.domain.PaymentTransaction;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.domain.ShipmentRefCounter;
import com.oneday.orders.domain.ShipmentRefCounterId;
import com.oneday.orders.domain.ShipmentStateHistory;
import com.oneday.orders.domain.enums.PaymentStatus;
import com.oneday.orders.domain.enums.TriggerSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

class TestFixtures {

    static Address address(String city) {
        Address a = new Address();
        a.setLine1("1 Test Street");
        a.setCity(city);
        a.setPincode("110001");
        a.setState("Test State");
        return a;
    }

    static Shipment shipment(String ref, String cityId) {
        Shipment s = new Shipment();
        s.setShipmentRef(ref);
        s.setCustomerType(CustomerType.B2C);
        s.setDeliveryType(DeliveryType.INTERCITY);
        s.setSenderName("Alice Sender");
        s.setSenderPhone("9000000001");
        s.setOriginAddress(address("Delhi"));
        s.setOriginCity("DEL");
        s.setOriginPincode("110001");
        s.setDestAddress(address("Mumbai"));
        s.setDestCity("BOM");
        s.setDestPincode("400001");
        s.setReceiverName("Bob Receiver");
        s.setReceiverPhone("9000000002");
        s.setWeightGrams(500);
        s.setLengthCm((short) 10);
        s.setWidthCm((short) 10);
        s.setHeightCm((short) 10);
        s.setVolumetricWeightGrams(500);
        s.setChargeableWeightGrams(500);
        s.setQuotedPricePaise(10000L);
        s.setTaxPaise(1800L);
        s.setTotalPricePaise(11800L);
        s.setRateCardVersion("v1.0");
        s.setPickupType(PickupType.DA_PICKUP);
        s.setDropType(DropType.DA_DELIVERY);
        s.setState(ShipmentState.BOOKED);
        s.setCityId(cityId);
        return s;
    }

    static ShipmentStateHistory stateHistory(UUID shipmentId, ShipmentState toState, Instant occurredAt) {
        return ShipmentStateHistory.builder()
                .shipmentId(shipmentId)
                .toState(toState)
                .triggeredBy("test-user")
                .triggerSource(TriggerSource.SYSTEM)
                .occurredAt(occurredAt)
                .build();
    }

    static IdempotencyKey idempotencyKey(String key, UUID userId, Instant expiresAt) {
        IdempotencyKey ik = new IdempotencyKey();
        ik.setId(new IdempotencyKeyId(key, userId));
        ik.setResponseStatus((short) 200);
        ik.setResponseBody("{\"status\":\"ok\"}");
        ik.setExpiresAt(expiresAt);
        return ik;
    }

    static ShipmentRefCounter refCounter(String cityCode, LocalDate dateKey, int nextVal) {
        ShipmentRefCounter rc = new ShipmentRefCounter();
        rc.setId(new ShipmentRefCounterId(cityCode, dateKey));
        rc.setNextVal(nextVal);
        return rc;
    }

    static B2bAccount b2bAccount(String email, String cityId) {
        B2bAccount acc = new B2bAccount();
        acc.setAccountName("Acme Corp");
        acc.setBillingEmail(email);
        acc.setCreditLimitPaise(1_000_000L);
        acc.setOutstandingBalancePaise(0L);
        acc.setPaymentTermsDays((short) 30);
        acc.setCityId(cityId);
        acc.setIsActive(true);
        return acc;
    }

    static PaymentTransaction paymentTransaction(UUID shipmentId, String razorpayOrderId) {
        PaymentTransaction tx = new PaymentTransaction();
        tx.setShipmentId(shipmentId);
        tx.setRazorpayOrderId(razorpayOrderId);
        tx.setAmountPaise(10000L);
        tx.setTaxPaise(1800L);
        tx.setTotalPaise(11800L);
        tx.setCurrency("INR");
        tx.setStatus(PaymentStatus.CREATED);
        return tx;
    }
}
