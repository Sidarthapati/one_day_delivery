-- COD carried through the bulk cart: the goods value to collect from the buyer on delivery.
-- Null / 0 ⇒ prepaid. Only meaningful on B2B checkout (B2C cart is always prepaid).
ALTER TABLE cart_item
    ADD COLUMN cod_amount_to_collect_paise BIGINT;
