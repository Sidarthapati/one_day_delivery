# Discussion-4 (M1) — Team-member budget toggle: scope

_Branch `feat/b2b-member-budget-toggle`. Extends Discussion-3 (vi); verified against merged vi code on main._

## What exists (from vi, merged)
- `b2b_account_member.spend_limit_paise` (nullable): **null = unlimited**, a value = **fixed monthly cap**.
- Budget check in `B2bBookingServiceImpl.persistB2b` (`:250-258`): if `spendLimitPaise != null`, gate `spent-this-month + booking > spendLimitPaise` (calendar month, IST, `sumMemberSpendSince`). Member row locked `FOR UPDATE`.
- API: `MemberBudgetRequest(spendLimitPaise)` → `PUT /api/v1/b2b/members/{userId}/budget` → `B2bMemberService.setSpendLimit`.
- Response: `MemberResponse(… spendLimitPaise, spentThisMonthPaise, remainingPaise)`.
- Web: Team page **Set/Edit budget** modal (a ₹ `NumberInput`, Remove clears it) + a "₹X of ₹Y" column.

## The ask (M1)
> Admin defaults new teammates to **full** access; a limited user gets a **fixed amount OR a percentage** cap (of credit). Toggle in the business console.

So vi covered *unlimited* and *fixed*; M1 adds the **percentage** option and turns the control into an explicit **Unlimited / Fixed / Percentage** toggle.

## Build

### Data — `V4_57__b2b_member_spend_limit_pct.sql`
Add one nullable column to `b2b_account_member`:
```sql
ALTER TABLE b2b_account_member ADD COLUMN spend_limit_pct SMALLINT;  -- 1..100, NULL = not a % cap
```
A member is now exactly one of three states (mutually exclusive, enforced in the service):
- **Unlimited** — both `spend_limit_paise` and `spend_limit_pct` NULL.
- **Fixed** — `spend_limit_paise` set.
- **Percentage** — `spend_limit_pct` set (a % of the account's credit limit).

### Enforcement — resolve an *effective* cap
**Decision: a % cap is a % of the account's *available credit* (`credit_limit − outstanding`), computed live at booking time** — so a member's headroom moves as the account spends. Reuse vi's month-sum gate unchanged:
```java
Long cap = null;
if (member.getSpendLimitPct() != null) {      // % of available credit, right now
    long available = Math.max(0, account.getCreditLimitPaise() - account.getOutstandingBalancePaise());
    cap = available * member.getSpendLimitPct() / 100;
} else if (member.getSpendLimitPaise() != null) { // fixed
    cap = member.getSpendLimitPaise();
}
if (cap != null && spent + booking > cap) throw MemberBudgetExceededException(...);
```
`account` is locked `findByIdForUpdate` in `persistB2b`, so `outstanding` is read before this booking is added. Same calendar-month window for the member's own spend.

### API + response
- `MemberBudgetRequest(Long spendLimitPaise, Integer spendLimitPct)` — the owner sends one, or both null to clear. Service **validates mutual exclusivity** (422 if both set) and `spendLimitPct ∈ 1..100`.
- `setSpendLimit(...)` → `setBudget(accountId, caller, target, paise, pct)`; owner-only, owner not cappable (unchanged).
- `MemberResponse` gains `spendLimitPct` + a resolved **`effectiveLimitPaise`** (so the UI shows "₹X of ₹Y" for both fixed and % members); `remainingPaise` computed off the effective cap.

### Web (business)
**Decision: the toggle appears in BOTH the add-teammate invite flow and the Edit-budget modal.**
- A shared **BudgetPicker** (SegmentedControl: Unlimited · Fixed · Percentage → a ₹ or % `NumberInput`) used in:
  - the **Edit-budget modal** (existing), and
  - the **invite/add-teammate flow** — so the admin sets access at add time (defaults to Unlimited).
- The invite call carries the initial budget (extend `members.add` to accept an optional budget, or set it in a second `setBudget` call right after invite — the former is cleaner, one round-trip).
- The row's budget column shows the effective cap: fixed → "₹X of ₹Y", percentage → "₹X of ₹Y (Z% of credit)".

## Verify
A **percentage-capped** member is blocked once their month spend passes `credit_limit × pct%` while the account still has credit; a **fixed** member behaves as in vi; an **unlimited** member (and the owner) book freely. `mvn test -pl orders` + `pnpm --filter @oneday/business typecheck` green.

## Reuse / not rebuild
Don't re-model membership or the month-sum gate — M1 only adds the `pct` dimension and the effective-cap resolution. Migration is **V4_57** (V4_56 is the current head).
