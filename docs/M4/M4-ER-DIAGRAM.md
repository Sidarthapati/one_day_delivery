# M4 — Entity Relationship Diagram

> Generated from [M4-ORDERS-DESIGN.md](M4-ORDERS-DESIGN.md) §10 (Database Schema).  
> Update this file whenever the schema changes.

```mermaid
erDiagram
    SHIPMENTS {
        uuid id PK
        varchar shipment_ref UK
        enum customer_type
        enum delivery_type
        uuid b2b_account_id FK
        varchar origin_city
        varchar origin_pincode
        jsonb origin_address
        varchar dest_city
        varchar dest_pincode
        jsonb dest_address
        varchar sender_name
        varchar sender_phone
        varchar sender_email
        varchar receiver_name
        varchar receiver_phone
        varchar receiver_email
        integer weight_grams
        smallint length_cm
        smallint width_cm
        smallint height_cm
        integer volumetric_weight_grams
        integer chargeable_weight_grams
        bigint declared_value_paise
        bigint quoted_price_paise
        bigint tax_paise
        bigint total_price_paise
        bigint final_price_paise
        varchar rate_card_version
        enum state
        smallint sla_commitment_minutes
        timestamptz eta_promised
        timestamptz eta_updated
        uuid assigned_flight_id
        uuid origin_tile_id
        varchar parcel_id
        enum payment_mode
        uuid payment_id FK
        varchar idempotency_key
        timestamptz cancelled_at
        varchar cancellation_reason
        timestamptz archived_at
        varchar city_id
        timestamptz created_at
        timestamptz updated_at
    }

    SHIPMENT_STATE_HISTORY {
        uuid id PK
        uuid shipment_id FK
        enum from_state
        enum to_state
        varchar triggered_by
        varchar trigger_source
        varchar event_ref
        text notes
        timestamptz occurred_at
    }

    PAYMENT_TRANSACTIONS {
        uuid id PK
        uuid shipment_id FK
        varchar razorpay_order_id UK
        varchar razorpay_payment_id
        varchar razorpay_signature
        bigint amount_paise
        bigint tax_paise
        bigint total_paise
        varchar currency
        varchar status
        varchar refund_id
        varchar refund_status
        bigint refund_amount_paise
        varchar payment_method
        timestamptz created_at
        timestamptz updated_at
    }

    B2B_ACCOUNTS {
        uuid id PK
        varchar account_name
        varchar gstin
        varchar billing_email
        bigint credit_limit_paise
        bigint outstanding_balance_paise
        smallint payment_terms_days
        uuid rate_card_id
        varchar webhook_url
        varchar webhook_secret
        varchar city_id
        boolean is_active
        timestamptz created_at
        timestamptz updated_at
    }

    IDEMPOTENCY_KEYS {
        varchar key PK
        uuid user_id PK
        smallint response_status
        jsonb response_body
        timestamptz expires_at
        timestamptz created_at
    }

    SHIPMENT_REF_COUNTERS {
        varchar city_code PK
        date date_key PK
        integer next_val
    }

    PICKUP_OTPS {
        uuid id PK
        uuid shipment_id FK
        varchar otp_hash
        timestamptz expires_at
        boolean used
        smallint resend_count
        timestamptz created_at
    }

    SHIPMENTS ||--o{ SHIPMENT_STATE_HISTORY : "has history"
    SHIPMENTS ||--o| PAYMENT_TRANSACTIONS : "paid via"
    SHIPMENTS }o--o| B2B_ACCOUNTS : "billed to"
    SHIPMENTS ||--o| PICKUP_OTPS : "has active OTP"
```

## Notes

- `SHIPMENT_STATE_HISTORY` is append-only — rows are never updated or deleted.
- `PAYMENT_TRANSACTIONS` has one row per payment attempt; a COD shipment has no row until delivery is confirmed by M5.
- `B2B_ACCOUNTS.outstanding_balance_paise` is incremented atomically with each B2B booking (same DB transaction, `SELECT FOR UPDATE`).
- `IDEMPOTENCY_KEYS` rows are purged nightly when `expires_at < NOW()`. The `request_fingerprint` column (SHA-256 of canonicalised request body) was added in V4_10 (PR #8) alongside the fingerprinting logic that writes it.
- `PICKUP_OTPS` stores BCrypt-hashed OTPs for DA pickup verification. At most one row exists per shipment at any time (unique index on `shipment_id`). On resend the old row is deleted and a new one is inserted. Rows are not archived — they can be purged after expiry.
- `SHIPMENT_REF_COUNTERS` is a per-city-per-day sequence counter; see design doc §5.1 for the Redis INCR upgrade path at high volume.
- `assigned_flight_id` references M9's flight table (cross-module, not a DB foreign key — enforced at application level).
- `origin_tile_id` references M3's grid tile table (same cross-module rule).
