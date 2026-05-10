# M1 — Auth: Local Setup Guide

> **Documentation index for this module:**
> - [DESIGN.md](./DESIGN.md) — full technical spec: roles, permissions, REST API, domain model, security filter, audit trail
> - [SCENARIOS.md](./SCENARIOS.md) — narrative walkthrough of every auth flow (why the code is shaped the way it is)
> - [OPEN-QUESTIONS.md](./OPEN-QUESTIONS.md) — open product decisions that block or constrain implementation
> - [POSTGRES.md](./POSTGRES.md) — local database reference and useful queries

## Prerequisites

- Java 21
- Maven
- PostgreSQL 16 (running on port 5432)

## 1. Create the Database User and Database

Run once if you haven't already:

```bash
psql -U postgres -c "CREATE USER oneday WITH PASSWORD 'oneday';"
psql -U postgres -c "CREATE DATABASE oneday OWNER oneday;"
```

If prompted for a password, use your system postgres superuser password.

## 2. Build the Project

From the repo root:

```bash
mvn clean install
```

To build only the auth module:

```bash
mvn clean install -pl auth
```

## 3. Run the App

```bash
mvn spring-boot:run -pl app
```

The server starts on **http://localhost:8080**.

Flyway runs automatically on startup and applies migrations in order:
- `V1__create_auth_tables.sql` — creates `users`, `api_keys`, `role_audit_logs`
- `V2__seed_admin.sql` — inserts the bootstrap admin user

## 4. Bootstrap Admin Credentials

A default admin is seeded on first run:

| Field    | Value            |
|----------|------------------|
| Email    | `admin@oneday.in` |
| Password | `Admin1234!`     |
| Role     | `ADMIN`          |

Use these to log in and create real users via the API. Delete or replace this seed row before going to production.

## 5. Key API Endpoints

### Login (get a JWT)
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "admin@oneday.in", "password": "Admin1234!"}'
```

### Register a new user (ADMIN only)
```bash
curl -X POST http://localhost:8080/api/users \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"email": "ravi@blr.in", "password": "Pass1234!", "name": "Ravi Kumar", "role": "DELIVERY_ASSOCIATE", "cityId": "BLR"}'
```

### Check your own permissions
```bash
curl http://localhost:8080/api/permissions/me \
  -H "Authorization: Bearer <token>"
```

## 6. Connect to the Database

```bash
PGPASSWORD=oneday psql -h localhost -U oneday -d oneday
```

See [POSTGRES.md](./POSTGRES.md) for the full query reference and psql command cheatsheet.
