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

echo "4) the NEW token (RT2) is now dead too (family was revoked) → expect 401"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/auth/refresh" -H "$J" \
  -d "{\"refreshToken\":\"$RT2\"}")
[ "$CODE" = "401" ] || fail "RT2 returned $CODE after family revocation, expected 401"
pass "successor token also dead — whole family revoked on theft signal"

echo "5) logout is idempotent → expect 204 even for an already-dead token"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/auth/logout" -H "$J" \
  -d "{\"refreshToken\":\"$RT2\"}")
[ "$CODE" = "204" ] || fail "logout returned $CODE, expected 204"
pass "logout idempotent (204)"

echo
pass "ALL CHECKS PASSED — refresh-token rotation + revocation is live at $BASE_URL"
