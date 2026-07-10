package com.oneday.orders.service;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.orders.dto.AddCartItemRequest;
import com.oneday.orders.dto.CartCheckoutRequest;
import com.oneday.orders.dto.CartCheckoutResponse;
import com.oneday.orders.dto.CartItemResponse;
import com.oneday.orders.dto.CartResponse;

import java.util.List;
import java.util.UUID;

/**
 * Per-user shipment cart: add/list/edit/remove independent shipment drafts, then checkout to
 * book them all. B2C checkout settles one aggregate Razorpay payment for the cart total; B2B
 * checkout debits the account credit line per item. Items that fail validation stay in the cart.
 */
public interface CartService {

    CartResponse getCart(UUID userId);

    CartItemResponse addItem(UUID userId, AddCartItemRequest request);

    /**
     * Adds many items in a single transaction: the open cart is fetched/created once and all
     * priced, serviceable items are inserted as one batch (bulk upload uses this instead of
     * calling {@link #addItem} per row, which opened a transaction and re-looked-up the cart
     * for every row). Each result aligns positionally with {@code requests}; a row that fails
     * pricing/serviceability is reported as a failure and simply omitted from the insert — it
     * does not roll back the others (pricing performs no writes).
     */
    List<BulkItemResult> addItems(UUID userId, List<AddCartItemRequest> requests);

    /** Outcome of one row in {@link #addItems}: {@code added} true, or a {@code failureReason}. */
    record BulkItemResult(boolean added, String failureReason) {
        public static BulkItemResult ok() { return new BulkItemResult(true, null); }
        public static BulkItemResult fail(String reason) { return new BulkItemResult(false, reason); }
    }

    CartItemResponse updateItem(UUID userId, UUID itemId, AddCartItemRequest request);

    void removeItem(UUID userId, UUID itemId);

    /**
     * @param customerType lane derived from the caller's role (C2C/B2C → gateway, B2B → credit)
     */
    CartCheckoutResponse checkout(UUID userId, CustomerType customerType, CartCheckoutRequest request);

    /** Cart has no items to check out → 422. */
    class EmptyCartException extends RuntimeException {
        public EmptyCartException(String message) { super(message); }
    }

    /** Required checkout fields missing for the lane (razorpay for B2C, account for B2B) → 422. */
    class CheckoutValidationException extends RuntimeException {
        public CheckoutValidationException(String message) { super(message); }
    }

    /** Cart item id not found in the user's open cart → 404. */
    class CartItemNotFoundException extends RuntimeException {
        public CartItemNotFoundException(String message) { super(message); }
    }
}
