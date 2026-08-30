-- Soft-delete for merchant categories. Deleting a category shouldn't erase the history of the parcels
-- already tagged with it: an archived category keeps its row (so reports still show the real name), but
-- disappears from the pick list. archived_at NULL = live.
ALTER TABLE merchant_category ADD COLUMN archived_at TIMESTAMPTZ;

-- The name-uniqueness rule now applies only among live categories, so a merchant can reuse the name of a
-- category they've archived (e.g. re-run a seasonal "Diwali Sale" next year).
DROP INDEX ux_merchant_category_account_name;
CREATE UNIQUE INDEX ux_merchant_category_account_name
  ON merchant_category (b2b_account_id, lower(name)) WHERE archived_at IS NULL;
