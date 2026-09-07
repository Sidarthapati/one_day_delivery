# Discussion-3 (vi) — Per-user RBAC + per-member budget: scope

_Branch `feat/b2b-member-budget`. Scopes the **budget check** first (the concrete, enforceable half); role expansion is a lighter companion, noted at the end._

## The decisive finding: members can't book today

All B2B bookings funnel through **one** method — `B2bBookingServiceImpl.book()` — from every entry point:
- single shipment: `B2bShipmentController:63`
- cart checkout: `CartServiceImpl:234` (one `book()` per item, caller = `cart.userId`)
- order repair: `OrderRepairServiceImpl:80`

And that method enforces **owner-only** access:

```java
// B2bBookingServiceImpl:141-146
if (account.getOwnerUserId() == null || !account.getOwnerUserId().toString().equals(userId)) {
    throw new AccountAccessException("Caller is not authorized for B2B account: " + ...);
}
```

The membership table (`b2b_account_member`, `V4_44`, built in Discussion-2 xii) **is never consulted in the booking path** — booking checks `b2b_accounts.owner_user_id` only. So a MEMBER cannot book at all today. The split doc's "any member draws the account credit" is aspirational; the code blocks non-owners.

**Consequence:** a per-member budget is meaningless until members can book. So (vi) is two parts, in order.

---

## Part A — precondition: membership-based booking authorization

Replace the owner-only guard in `book()` with a membership check (the single chokepoint covers all three entry points):

- Resolve the caller's membership: `B2bAccountMemberRepository.findByB2bAccountIdAndUserId(accountId, userId)` (already exists).
- **Allow** if the caller is an OWNER **or** MEMBER of the account; **fail closed** (`AccountAccessException`, 403) otherwise. The owner still works — the `V4_44` backfill made every owner an `OWNER` member row.
- Keep the null-safety of the current guard (no membership row → reject).

This is a behaviour change (members gain booking) — call it out in the PR; it's what makes budgets enforceable.

## Part B — per-member budget (the check itself)

### Data
`V4_51__b2b_member_spend_limit.sql` — one nullable column on `b2b_account_member`:

```sql
ALTER TABLE b2b_account_member ADD COLUMN spend_limit_paise BIGINT;  -- NULL = unlimited
```
Mirror it on `B2bAccountMember` (a `Long spendLimitPaise`, same style as the existing `kycStatus` field).

**No running-counter column.** Spend is summed on demand (below) — that avoids a period-reset job and cancel/refund reversal bookkeeping. Add a counter only if the sum query ever measures as a hotspot (it won't at pilot volume). `// ponytail: sum-on-check, add a counter if booking QPS ever makes the SELECT-sum hurt`.

### The check (inside the existing booking TX)
In `persistB2b`, right beside the credit check (`:239-248`), after the account is locked `findByIdForUpdate`:

1. Load the caller's member row **`FOR UPDATE`** (new `B2bAccountMemberRepository.findByAccountAndUserForUpdate`) so a member's concurrent bookings serialize on the budget — mirrors the account-row lock pattern already used for credit.
2. If `spendLimitPaise == null` → no cap, skip (OWNER, or a member with no limit set).
3. Else compute this-period spend + this booking and gate:
   ```java
   long spent = shipmentRepository.sumMemberSpendSince(userId, periodStart); // non-cancelled totals
   if (spent + quote.totalPricePaise() > member.getSpendLimitPaise())
       throw new MemberBudgetExceededException(...); // new, maps to 402
   ```
4. New repo query: `sumMemberSpendSince(UUID bookedByUserId, Instant since)` → `SUM(total_price_paise)` over shipments where `booked_by_user_id = ?` and `created_at >= ?` and `state <> 'CANCELLED'`. `bookedByUserId` is already persisted on every shipment (`:300`); no new write.

### Basis, exemptions, semantics (decisions baked in)
- **Basis** = `quote.totalPricePaise()` — the shipping charge, same basis as the credit check. **COD amount is not counted** (it's buyer→vendor money, not the member's spend).
- **Budget is independent of account credit** — both must pass: a member can be blocked by their own limit while the account still has credit (this is the whole point). Order: member-budget check, then the existing account-credit check.
- **Exempt:** OWNER and any member with `spend_limit_paise = NULL` (unlimited). Budget bites only when a limit is set.
- **Period = calendar month** (see open question — the one real decision).

### Admin + web
- `MembersController` / `B2bMemberService`: set/clear a member's `spend_limit_paise` (owner-only, existing owner guard on member admin).
- `MemberResponse`: surface `spendLimitPaise` + `spentThisPeriodPaise` + `remainingPaise`.
- `oneday-web` business **team** page: a budget field per member + "₹X of ₹Y used" surface. Typecheck `@oneday/business`.

---

## Decisions (resolved)
- **Budget period = calendar month.** The limit is per calendar month; sum-on-check enforces it with `created_at >= start-of-month` (IST) — no reset job. A `spend_period` column can generalise later if the business ever wants lifetime/billing-cycle windows.
- **Scope of this branch = budget only.** The RBAC/roles expansion is a **separate follow-up PR**, not this one. This keeps the diff focused and lands the enforceable budget sooner. (When built, the role gate sits at the same `book()` chokepoint — e.g. a view-only member blocked from booking.)

## Verification (end-to-end)
A member with `spend_limit_paise` set is **blocked past it while the account still has credit**; a member with no limit, and the owner, book freely; role (if built) gates who can book at all. (`mvn test -pl orders` + `pnpm --filter @oneday/business typecheck` green before push.)

## Status
- **Backend done** (branch `feat/b2b-member-budget`): Part A (membership-based booking) + Part B (V4_51 migration, `spendLimitPaise` on `B2bAccountMember`, `FOR UPDATE` member lock + monthly sum-on-check in `B2bBookingServiceImpl`, `MemberBudgetExceededException` → 402, `PUT /api/v1/b2b/members/{userId}/budget`, `MemberResponse` spend fields). Orders unit tests **198 green** (5 new/rewritten in `B2bBookingServiceImplTest`, member test updated).
- **Pre-existing red (not this change):** the `orders` **e2e** suite fails to load its Spring context — `CodCashServiceImpl` needs an `auth.UserService` bean the e2e `OrdersTestApplication` doesn't provide (merged with the DA-cod-ledger on main; identical failure with this branch stashed). Flagged, out of scope here.
- **Web pending:** business **team** page — per-member budget field + "₹X of ₹Y used"; wire `PUT …/budget` into `@oneday/api`.
