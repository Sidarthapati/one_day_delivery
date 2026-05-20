CREATE TABLE b2b_accounts (
  id                        UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  account_name              VARCHAR(200) NOT NULL,
  gstin                     VARCHAR(15),
  billing_email             VARCHAR(254) NOT NULL,
  credit_limit_paise        BIGINT       NOT NULL DEFAULT 0,
  outstanding_balance_paise BIGINT       NOT NULL DEFAULT 0,
  payment_terms_days        SMALLINT     NOT NULL DEFAULT 30,
  rate_card_id              UUID,
  webhook_url               VARCHAR(500),
  webhook_secret            VARCHAR(100),
  city_id                   VARCHAR(10)  NOT NULL,
  is_active                 BOOLEAN      NOT NULL DEFAULT TRUE,
  created_at                TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  updated_at                TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TRIGGER trg_b2b_accounts_updated_at
  BEFORE UPDATE ON b2b_accounts
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
