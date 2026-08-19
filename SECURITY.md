# Security Policy

> **Status:** placeholder contact details — replace `security@godspeed.delivery` and the domain with
> the real entity inbox/domain before go-live. The disclosure endpoint is served at
> `/.well-known/security.txt` (RFC 9116).

## Reporting a vulnerability

Please email **security@godspeed.delivery** with:

- a description of the issue and its impact,
- steps to reproduce (PoC appreciated),
- affected endpoint(s)/component(s) and any relevant IDs (never include other users' personal data).

Do **not** open a public GitHub issue for security reports. We aim to acknowledge within **2 business
days** and to provide a remediation timeline within **7 business days**.

Please act in good faith: no data exfiltration beyond what's needed to demonstrate the issue, no
service degradation (DoS/load testing), and no access to other users' data.

## Supported

Only the currently deployed version (the `main` branch / latest staging deploy) is supported.

## What we run

- Backend: Java 21 / Spring Boot 3.2 monolith on Render (single web service), Postgres 16, RabbitMQ.
- Frontends: six Next.js consoles on Vercel; a React Native driver app.
- Auth is stateless JWT + hashed API keys. Transport is TLS-terminated at the platform edge.

## Hardening already in place

Dependency + container scanning (CodeQL/Trivy/gitleaks, per-PR + scheduled), SHA-256 idempotency,
`SELECT FOR UPDATE` on money/credit paths, structured JSON audit logging, DLQ topology, Razorpay
webhook HMAC verification, and (under the `prod` profile) rate limiting, security headers/HSTS/CSP,
locked CORS, JWT-secret fail-fast, and a mock-bean boot guard. See `docs/prod-readiness/PROD-READINESS-NOW.md`.
