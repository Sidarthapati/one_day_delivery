package com.oneday.orders.e2e;

import com.oneday.common.domain.enums.DropType;
import com.oneday.common.domain.enums.PickupType;
import com.oneday.common.port.dto.ServiceabilityResult;
import com.oneday.orders.dto.AddCartItemRequest;
import com.oneday.orders.dto.CartCheckoutRequest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage for the shipment cart: add → list → checkout for both lanes, B2B credit
 * debit, partial-failure handling (non-serviceable item stays in the cart), and empty-cart guard.
 */
class CartE2eTest extends OrdersE2eSupport {

    private AddCartItemRequest cartItem(String originPincode, String destPincode) {
        AddCartItemRequest r = new AddCartItemRequest();
        r.setSenderName("Ravi Sender");
        r.setSenderPhone("+919000000001");
        r.setOriginAddress(addr("1 Connaught Place", "Delhi", originPincode, "DL"));
        r.setOriginCity("DEL");
        r.setOriginPincode(originPincode);
        r.setReceiverName("Priya Receiver");
        r.setReceiverPhone("+919000000002");
        r.setDestAddress(addr("1 MG Road", "Bengaluru", destPincode, "KA"));
        r.setDestCity("BLR");
        r.setDestPincode(destPincode);
        r.setWeightGrams(1000);
        r.setLengthCm((short) 20);
        r.setWidthCm((short) 15);
        r.setHeightCm((short) 10);
        r.setDeclaredValuePaise(500000L);
        r.setPickupType(PickupType.DA_PICKUP);
        r.setDropType(DropType.DA_DELIVERY);
        return r;
    }

    private void addItem(String token, AddCartItemRequest item) throws Exception {
        mvc.perform(post("/api/v1/cart/items")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(json.writeValueAsString(item)))
                .andExpect(status().isCreated());
    }

    private CartCheckoutRequest b2cCheckout() {
        CartCheckoutRequest c = new CartCheckoutRequest();
        c.setRazorpayOrderId("order_test_1");
        c.setRazorpayPaymentId("pay_test_1");
        c.setRazorpaySignature("sig_test_1");
        return c;
    }

    @Test
    void b2c_add_list_checkout_booksAllItems() throws Exception {
        String token = tokenFor("B2C_CUSTOMER", randomUserId());
        addItem(token, cartItem("110001", "560001"));
        addItem(token, cartItem("110002", "560002"));

        // cart shows 2 items and the rolled-up total (2 × 4720)
        mvc.perform(get("/api/v1/cart").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item_count", is(2)))
                .andExpect(jsonPath("$.cart_total_paise", is(9440)));

        // checkout: one aggregate payment, both booked, cart closed
        mvc.perform(post("/api/v1/cart/checkout")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey())
                        .contentType("application/json")
                        .content(json.writeValueAsString(b2cCheckout())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.booked", is(2)))
                .andExpect(jsonPath("$.failed", is(0)))
                .andExpect(jsonPath("$.charged_total_paise", is(9440)))
                .andExpect(jsonPath("$.cart_status", is("CHECKED_OUT")));
    }

    @Test
    void b2c_partialFailure_keepsBadItemInOpenCart() throws Exception {
        String token = tokenFor("B2C_CUSTOMER", randomUserId());
        // Both items are serviceable when added to the cart (base stub).
        addItem(token, cartItem("110001", "560001"));   // stays good
        addItem(token, cartItem("110002", "560002"));   // will go stale below

        // Simulate a nightly grid replan: the second route is no longer serviceable at checkout.
        lenient().when(serviceabilityPort.check(argThat(q -> q != null && "560002".equals(q.destPincode()))))
                .thenReturn(new ServiceabilityResult(false, null, null, null));

        mvc.perform(post("/api/v1/cart/checkout")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey())
                        .contentType("application/json")
                        .content(json.writeValueAsString(b2cCheckout())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.booked", is(1)))
                .andExpect(jsonPath("$.failed", is(1)))
                .andExpect(jsonPath("$.charged_total_paise", is(4720)))   // only the good item charged
                .andExpect(jsonPath("$.cart_status", is("OPEN")));        // bad item remains

        // the failed item is still in the open cart
        mvc.perform(get("/api/v1/cart").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item_count", is(1)));
    }

    @Test
    void b2b_checkout_booksAgainstCreditAccount() throws Exception {
        // The owner of the demo B2B account (₹50k limit, ₹12k outstanding) checks out via credit.
        String token = tokenFor("B2B_USER", B2B_OWNER_USER_ID);
        addItem(token, cartItem("110001", "560001"));

        CartCheckoutRequest c = new CartCheckoutRequest();
        c.setB2bAccountId(UUID.fromString(B2B_ACCOUNT_ID));

        mvc.perform(post("/api/v1/cart/checkout")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey())
                        .contentType("application/json")
                        .content(json.writeValueAsString(c)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.booked", is(1)))
                .andExpect(jsonPath("$.failed", is(0)))
                .andExpect(jsonPath("$.cart_status", is("CHECKED_OUT")));
    }

    @Test
    void checkout_emptyCart_returns422() throws Exception {
        String token = tokenFor("B2C_CUSTOMER", randomUserId());
        mvc.perform(post("/api/v1/cart/checkout")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey())
                        .contentType("application/json")
                        .content(json.writeValueAsString(b2cCheckout())))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void b2c_checkout_missingPaymentRefs_returns422() throws Exception {
        String token = tokenFor("B2C_CUSTOMER", randomUserId());
        addItem(token, cartItem("110001", "560001"));
        mvc.perform(post("/api/v1/cart/checkout")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey())
                        .contentType("application/json")
                        .content(json.writeValueAsString(new CartCheckoutRequest())))
                .andExpect(status().isUnprocessableEntity());
    }
}
