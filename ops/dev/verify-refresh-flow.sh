#!/usr/bin/env bash
# Stage 1 — prove the B2.1 refresh-token flow end to end against a running app.
# Verifies: login returns a refreshToken → /auth/refresh rotates → the OLD token is rejected (reuse
# detection, 401) → /auth/logout revokes. Read-only except for the tokens it mints for itself.
#
# Usage:
#   BASE_URL=https://one-day-delivery.onrender.com \
#   EMAIL=you@example.com PASSWORD=secret \
#   ops/dev/verify-refresh-flow.sh
set -euo pipefail

BASE_URL="${BASE_URL:?set BASE_URL to the running app}"
EMAIL="${EMAIL:?set EMAIL}"
PASSWORD="${PASSWORD:?set PASSWORD}"
J='Content-Type: application/json'
pass() { printf '\033[32m✔ %s\033[0m\n' "$*"; }
fail() { printf '\033[31mFAIL: %s\033[0m\n' "$*"; exit 1; }

# jq is optional; fall back to grep/sed if absent.
getfield() { # getfield <json> <field>
  if command -v jq >/dev/null 2>&1; then jq -r ".$2 // empty" <<<"$1"
  else sed -nE "s/.*\"$2\"[[:space:]]*:[[:space:]]*\"([^\"]*)\".*/\1/p" <<<"$1"; fi
}

echo "1) login → expect a refreshToken in the response"
LOGIN=$(curl -fsS -X POST "$BASE_URL/auth/login" -H "$J" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")
RT1=$(getfield "$LOGIN" refreshToken)
AT1=$(getfield "$LOGIN" token)
[ -n "$AT1" ] || fail "no access token in login response"
[ -n "$RT1" ] || fail "no refreshToken in login response (is godspeed.refresh.enabled=true?)"
pass "login returned an access token + refresh token"

echo "2) refresh with the refresh token → expect a NEW access + NEW refresh token"
REFRESH=$(curl -fsS -X POST "$BASE_URL/auth/refresh" -H "$J" \
  -d "{\"refreshToken\":\"$RT1\"}")
RT2=$(getfield "$REFRESH" refreshToken)
AT2=$(getfield "$REFRESH" token)
[ -n "$RT2" ] && [ "$RT2" != "$RT1" ] || fail "refresh did not rotate the refresh token"
[ -n "$AT2" ] || fail "refresh did not return a new access token"
pass "refresh rotated: got a new access + new refresh token"

echo "3) replay the OLD refresh token → expect 401 (reuse detection revokes the family)"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/auth/refresh" -H "$J" \
  -d "{\"refreshToken\":\"$RT1\"}")
[ "$CODE" = "401" ] || fail "reuse of the old token returned $CODE, expected 401"
pass "old (already-rotated) token rejected with 401 — reuse detection works"

<<<<<<< Updated upstream
echo "4) the NEW token (RT2) is now dead too (family was revoked) → expect 401"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/auth/refresh" -H "$J" \
  -d "{\"refreshToken\":\"$RT2\"}")
[ "$CODE" = "401" ] || fail "RT2 returned $CODE after family revocation, expected 401"
pass "successor token also dead — whole family revoked on theft signal"
=======
# The successor stays valid: an immediate replay of RT1 is a benign double-submit (within the reuse
# grace window), NOT theft — so the live session must survive it. (Genuine *later* replay revokes the
# whole family; that's covered by the unit test with a controlled clock, not a sub-second live smoke.)
echo "4) the successor RT2 is still valid (benign replay didn't nuke the session) → expect a new token"
REFRESH2=$(curl -fsS -X POST "$BASE_URL/auth/refresh" -H "$J" -d "{\"refresh_token\":\"$RT2\"}")
RT3=$(getfield "$REFRESH2" refresh_token)
[ -n "$RT3" ] || fail "RT2 refresh did not return a new token — the session was wrongly revoked by a benign replay"
pass "RT2 still valid — grace window prevented a spurious logout; rotated to RT3"
>>>>>>> Stashed changes

echo "5) logout(RT3) → expect 204"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/auth/logout" -H "$J" \
<<<<<<< Updated upstream
  -d "{\"refreshToken\":\"$RT2\"}")
=======
  -d "{\"refresh_token\":\"$RT3\"}")
>>>>>>> Stashed changes
[ "$CODE" = "204" ] || fail "logout returned $CODE, expected 204"
pass "logout returned 204"

echo "6) refresh(RT3) after logout → expect 401 (logout revoked it)"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/auth/refresh" -H "$J" \
  -d "{\"refresh_token\":\"$RT3\"}")
[ "$CODE" = "401" ] || fail "RT3 usable after logout (got $CODE), expected 401"
pass "logged-out token rejected with 401"

echo "7) logout is idempotent → expect 204 again for the already-revoked token"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/auth/logout" -H "$J" \
  -d "{\"refresh_token\":\"$RT3\"}")
[ "$CODE" = "204" ] || fail "second logout returned $CODE, expected 204"
pass "logout idempotent (204)"

echo
pass "ALL CHECKS PASSED — refresh-token rotation + revocation is live at $BASE_URL"
