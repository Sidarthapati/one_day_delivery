package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.PaymentMode;
import com.oneday.orders.domain.Cart;
import com.oneday.orders.domain.CartItem;
import com.oneday.orders.domain.enums.CartItemSource;
import com.oneday.orders.domain.enums.CartStatus;
import com.oneday.orders.dto.AddCartItemRequest;
import com.oneday.orders.dto.B2bBookingRequest;
import com.oneday.orders.dto.BookingRequest;
import com.oneday.orders.dto.BookingResponse;
import com.oneday.orders.dto.CartCheckoutRequest;
import com.oneday.orders.dto.CartCheckoutResponse;
import com.oneday.orders.dto.CartItemResponse;
import com.oneday.orders.dto.CartResponse;
import com.oneday.orders.repository.CartItemRepository;
import com.oneday.orders.repository.CartRepository;
import com.oneday.orders.service.B2bBookingService;
import com.oneday.orders.service.BookingService;
import com.oneday.orders.service.CartService;
import com.oneday.orders.service.PaymentPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

@Service
class CartServiceImpl implements CartService {

    private static final Logger log = LoggerFactory.getLogger(CartServiceImpl.class);

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final BookingService bookingService;
    private final B2bBookingService b2bBookingService;
    private final PaymentPort paymentPort;
    private final TransactionTemplate txTemplate;
    private final ExecutorService bulkPricingExecutor;

    CartServiceImpl(CartRepository cartRepository, CartItemRepository cartItemRepository,
                    BookingService bookingService, B2bBookingService b2bBookingService,
                    PaymentPort paymentPort, TransactionTemplate txTemplate,
                    @Qualifier("bulkPricingExecutor") ExecutorService bulkPricingExecutor) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.bookingService = bookingService;
        this.b2bBookingService = b2bBookingService;
        this.paymentPort = paymentPort;
        this.txTemplate = txTemplate;
        this.bulkPricingExecutor = bulkPricingExecutor;
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public CartResponse getCart(UUID userId) {
        Cart cart = cartRepository.findByUserIdAndStatus(userId, CartStatus.OPEN).orElse(null);
        if (cart == null) {
            return new CartResponse(null, CartStatus.OPEN.name(), 0, 0L, List.of());
        }
        return toCartResponse(cart);
    }

    // ── Mutations ─────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public CartItemResponse addItem(UUID userId, AddCartItemRequest req) {
        Cart cart = getOrCreateOpenCart(userId);
        CartItem item = new CartItem();
        item.setCartId(cart.getId());
        item.setSource(CartItemSource.MANUAL);
        applyToItem(item, req);
        priceItem(item);                       // serviceability + quote (throws if not serviceable)
        return CartItemResponse.from(cartItemRepository.save(item));
    }

    @Override
    public List<BulkItemResult> addItems(UUID userId, List<AddCartItemRequest> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }

        // 1. Price every row CONCURRENTLY. Pricing (serviceability + quote) is read-only and
        //    stateless — each task builds its own CartItem, so there's no shared mutable state.
        //    Done outside any transaction so no DB connection is held during the quote calls.
        List<CompletableFuture<Priced>> futures = requests.stream()
                .map(req -> CompletableFuture.supplyAsync(() -> priceOne(req), bulkPricingExecutor))
                .toList();

        // 2. Collect in input order (each future carries either a priced item or a failure reason).
        List<BulkItemResult> results = new ArrayList<>(requests.size());
        List<CartItem> toSave = new ArrayList<>(requests.size());
        for (CompletableFuture<Priced> f : futures) {
            Priced p = f.join();                           // tasks never throw — failures are captured
            results.add(p.result());
            if (p.item() != null) {
                toSave.add(p.item());
            }
        }

        // 3. Persist the serviceable rows in ONE short transaction: cart fetch/create + batch insert.
        if (!toSave.isEmpty()) {
            txTemplate.executeWithoutResult(status -> {
                Cart cart = getOrCreateOpenCart(userId);
                toSave.forEach(item -> item.setCartId(cart.getId()));
                cartItemRepository.saveAll(toSave);
            });
        }
        return results;
    }

    /** Builds + prices one cart item off the booking thread; captures any pricing failure per row. */
    private Priced priceOne(AddCartItemRequest req) {
        CartItem item = new CartItem();
        item.setSource(CartItemSource.MANUAL);
        applyToItem(item, req);
        try {
            priceItem(item);                               // serviceability + quote (no DB writes)
            return new Priced(item, BulkItemResult.ok());
        } catch (RuntimeException e) {
            return new Priced(null, BulkItemResult.fail(reasonOf(e)));
        }
    }

    private record Priced(CartItem item, BulkItemResult result) {}

    @Override
    @Transactional
    public CartItemResponse updateItem(UUID userId, UUID itemId, AddCartItemRequest req) {
        CartItem item = requireItem(userId, itemId);
        applyToItem(item, req);
        priceItem(item);
        return CartItemResponse.from(cartItemRepository.save(item));
    }

    @Override
    @Transactional
    public void removeItem(UUID userId, UUID itemId) {
        cartItemRepository.delete(requireItem(userId, itemId));
    }

    // ── Checkout ──────────────────────────────────────────────────────────────

    @Override
    public CartCheckoutResponse checkout(UUID userId, CustomerType customerType, CartCheckoutRequest req) {
        Cart cart = cartRepository.findByUserIdAndStatus(userId, CartStatus.OPEN)
                .orElseThrow(() -> new EmptyCartException("No open cart to check out"));
        List<CartItem> items = cartItemRepository.findByCartIdOrderByCreatedAtAsc(cart.getId());
        if (items.isEmpty()) {
            throw new EmptyCartException("Cart is empty");
        }
        return customerType == CustomerType.B2B
                ? checkoutB2b(cart, items, req)
                : checkoutB2c(cart, items, customerType, req);
    }

    private CartCheckoutResponse checkoutB2c(Cart cart, List<CartItem> items,
                                             CustomerType customerType, CartCheckoutRequest req) {
        if (isBlank(req.getRazorpayOrderId()) || isBlank(req.getRazorpayPaymentId())
                || isBlank(req.getRazorpaySignature())) {
            throw new CheckoutValidationException(
                    "razorpayOrderId, razorpayPaymentId and razorpaySignature are required for a B2C checkout");
        }

        // 1. Re-price each item; non-serviceable ones become failures (stay in cart).
        List<CartItem> payable = new ArrayList<>();
        List<CartCheckoutResponse.Result> results = new ArrayList<>();
        long captureTotal = 0L;
        for (CartItem item : items) {
            try {
                captureTotal += bookingService.quote(toBookingRequest(item, customerType)).totalPricePaise();
                payable.add(item);
            } catch (RuntimeException e) {
                results.add(CartCheckoutResponse.Result.fail(item.getId(), reasonOf(e)));
            }
        }
        if (payable.isEmpty()) {
            return finish(cart, items.size(), null, null, 0L, results); // nothing to capture
        }

        // 2. Settle the single aggregate payment for the whole serviceable cart.
        final long total = captureTotal;
        paymentPort.verifySignature(req.getRazorpayOrderId(), req.getRazorpayPaymentId(), req.getRazorpaySignature());
        paymentPort.capture(req.getRazorpayPaymentId(), total);

        // 3. Persist each shipment without re-capturing.
        for (CartItem item : payable) {
            try {
                BookingResponse r = bookingService.bookSettled(
                        toBookingRequest(item, customerType), "cart-" + item.getId(), cart.getUserId().toString(), customerType);
                results.add(CartCheckoutResponse.Result.ok(item.getId(), r.getShipmentRef()));
                cartItemRepository.delete(item);
            } catch (RuntimeException e) {
                log.error("cart {} item {} captured but booking failed: {}", cart.getId(), item.getId(), e.toString());
                results.add(CartCheckoutResponse.Result.fail(item.getId(), reasonOf(e)));
            }
        }
        cart.setCheckoutRazorpayOrderId(req.getRazorpayOrderId());
        cart.setCheckoutRazorpayPaymentId(req.getRazorpayPaymentId());
        return finish(cart, items.size(), req.getRazorpayOrderId(), req.getRazorpayPaymentId(), total, results);
    }

    private CartCheckoutResponse checkoutB2b(Cart cart, List<CartItem> items, CartCheckoutRequest req) {
        if (req.getB2bAccountId() == null) {
            throw new CheckoutValidationException("b2bAccountId is required for a B2B checkout");
        }
        List<CartCheckoutResponse.Result> results = new ArrayList<>();
        long charged = 0L;
        for (CartItem item : items) {
            try {
                BookingResponse r = b2bBookingService.book(
                        toB2bRequest(item, req.getB2bAccountId()), "cart-" + item.getId(), cart.getUserId().toString());
                charged += r.getPricing().getTotalPricePaise();
                results.add(CartCheckoutResponse.Result.ok(item.getId(), r.getShipmentRef()));
                cartItemRepository.delete(item);
            } catch (RuntimeException e) {
                results.add(CartCheckoutResponse.Result.fail(item.getId(), reasonOf(e)));
            }
        }
        return finish(cart, items.size(), null, null, charged, results);
    }

    /** Marks the cart CHECKED_OUT only if every item booked; otherwise it stays OPEN with the failures. */
    private CartCheckoutResponse finish(Cart cart, int originalCount, String orderId, String paymentId,
                                        long charged, List<CartCheckoutResponse.Result> results) {
        int booked = (int) results.stream().filter(CartCheckoutResponse.Result::success).count();
        int failed = results.size() - booked;
        cart.setCheckoutTotalPaise(charged);
        cart.setCheckoutRazorpayOrderId(orderId);
        cart.setCheckoutRazorpayPaymentId(paymentId);
        boolean allBooked = booked == originalCount && failed == 0;
        cart.setStatus(allBooked ? CartStatus.CHECKED_OUT : CartStatus.OPEN);
        cartRepository.save(cart);
        return new CartCheckoutResponse(booked, failed, charged, cart.getStatus().name(), results);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Cart getOrCreateOpenCart(UUID userId) {
        return cartRepository.findByUserIdAndStatus(userId, CartStatus.OPEN).orElseGet(() -> {
            Cart c = new Cart();
            c.setUserId(userId);
            c.setStatus(CartStatus.OPEN);
            return cartRepository.save(c);
        });
    }

    private CartItem requireItem(UUID userId, UUID itemId) {
        Cart cart = cartRepository.findByUserIdAndStatus(userId, CartStatus.OPEN)
                .orElseThrow(() -> new CartItemNotFoundException("Cart item not found: " + itemId));
        return cartItemRepository.findByIdAndCartId(itemId, cart.getId())
                .orElseThrow(() -> new CartItemNotFoundException("Cart item not found: " + itemId));
    }

    /** Validates serviceability and caches the display quote; throws if the route is not serviceable. */
    private void priceItem(CartItem item) {
        long total = bookingService.quote(toBookingRequest(item, CustomerType.B2C)).totalPricePaise();
        item.setQuotedTotalPaise(total);
        item.setValidationStatus("VALID");
    }

    private CartResponse toCartResponse(Cart cart) {
        List<CartItem> items = cartItemRepository.findByCartIdOrderByCreatedAtAsc(cart.getId());
        long total = items.stream().mapToLong(i -> i.getQuotedTotalPaise() == null ? 0 : i.getQuotedTotalPaise()).sum();
        return new CartResponse(cart.getId(), cart.getStatus().name(), items.size(), total,
                items.stream().map(CartItemResponse::from).toList());
    }

    private static void applyToItem(CartItem item, AddCartItemRequest r) {
        item.setSenderName(r.getSenderName());
        item.setSenderPhone(r.getSenderPhone());
        item.setSenderEmail(r.getSenderEmail());
        item.setOriginAddress(r.getOriginAddress());
        item.setOriginCity(r.getOriginCity());
        item.setOriginPincode(r.getOriginPincode());
        item.setReceiverName(r.getReceiverName());
        item.setReceiverPhone(r.getReceiverPhone());
        item.setReceiverEmail(r.getReceiverEmail());
        item.setDestAddress(r.getDestAddress());
        item.setDestCity(r.getDestCity());
        item.setDestPincode(r.getDestPincode());
        item.setWeightGrams(r.getWeightGrams());
        item.setLengthCm(r.getLengthCm());
        item.setWidthCm(r.getWidthCm());
        item.setHeightCm(r.getHeightCm());
        item.setDeclaredValuePaise(r.getDeclaredValuePaise());
        item.setPickupType(r.getPickupType());
        item.setDropType(r.getDropType());
    }

    private static BookingRequest toBookingRequest(CartItem i, CustomerType type) {
        BookingRequest b = new BookingRequest();
        b.setSenderName(i.getSenderName());
        b.setSenderPhone(i.getSenderPhone());
        b.setSenderEmail(i.getSenderEmail());
        b.setOriginAddress(i.getOriginAddress());
        b.setOriginCity(i.getOriginCity());
        b.setOriginPincode(i.getOriginPincode());
        b.setReceiverName(i.getReceiverName());
        b.setReceiverPhone(i.getReceiverPhone());
        b.setReceiverEmail(i.getReceiverEmail());
        b.setDestAddress(i.getDestAddress());
        b.setDestCity(i.getDestCity());
        b.setDestPincode(i.getDestPincode());
        b.setWeightGrams(i.getWeightGrams());
        b.setLengthCm(i.getLengthCm());
        b.setWidthCm(i.getWidthCm());
        b.setHeightCm(i.getHeightCm());
        b.setDeclaredValuePaise(i.getDeclaredValuePaise());
        b.setPickupType(i.getPickupType());
        b.setDropType(i.getDropType());
        // Cart items are paid once at the cart level → mark PREPAID; bookSettled writes no txn.
        b.setPaymentMode(PaymentMode.PREPAID);
        return b;
    }

    private static B2bBookingRequest toB2bRequest(CartItem i, UUID accountId) {
        B2bBookingRequest b = new B2bBookingRequest();
        b.setB2bAccountId(accountId);
        b.setSenderName(i.getSenderName());
        b.setSenderPhone(i.getSenderPhone());
        b.setOriginAddress(i.getOriginAddress());
        b.setOriginCity(i.getOriginCity());
        b.setOriginPincode(i.getOriginPincode());
        b.setReceiverName(i.getReceiverName());
        b.setReceiverPhone(i.getReceiverPhone());
        b.setDestAddress(i.getDestAddress());
        b.setDestCity(i.getDestCity());
        b.setDestPincode(i.getDestPincode());
        b.setWeightGrams(i.getWeightGrams());
        b.setLengthCm(i.getLengthCm());
        b.setWidthCm(i.getWidthCm());
        b.setHeightCm(i.getHeightCm());
        b.setDeclaredValuePaise(i.getDeclaredValuePaise());
        b.setPickupType(i.getPickupType());
        b.setDropType(i.getDropType());
        return b;
    }

    private static String reasonOf(RuntimeException e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
