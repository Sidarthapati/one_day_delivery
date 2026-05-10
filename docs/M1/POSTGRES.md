# PostgreSQL — Local Dev Database

## Credentials

| Field    | Value       |
|----------|-------------|
| Host     | `localhost` |
| Port     | `5432`      |
| Database | `oneday`    |
| Username | `oneday`    |
| Password | `oneday`    |

Connection string: `jdbc:postgresql://localhost:5432/oneday`

## Connect from Terminal

```bash
PGPASSWORD=oneday psql -h localhost -U oneday -d oneday
```

Or set the password once per session:

```bash
export PGPASSWORD=oneday
psql -h localhost -U oneday -d oneday
```

## Useful psql Commands

> **Important:** Every SQL statement must end with a semicolon `;`. If you see `oneday->` instead of `oneday=>`, psql is waiting for one — just type `;` and hit Enter.

### Navigation

| Command           | Description                              |
|-------------------|------------------------------------------|
| `\dt`             | List all tables                          |
| `\d <table>`      | Describe a table (columns, indexes, FKs) |
| `\di`             | List all indexes                         |
| `\dn`             | List all schemas                         |
| `\l`              | List all databases                       |
| `\c <database>`   | Switch to a different database           |
| `\q`              | Quit                                     |

### Querying Data

```sql
-- Show all rows in a table
SELECT * FROM users;

-- Show specific columns
SELECT id, email, role FROM users;

-- Filter rows
SELECT * FROM users WHERE role = 'DELIVERY_ASSOCIATE';

-- Filter with multiple conditions
SELECT * FROM users WHERE role = 'DELIVERY_ASSOCIATE' AND city_id = 'BLR';

-- Sort results
SELECT * FROM users ORDER BY created_at DESC;

-- Limit number of rows returned
SELECT * FROM users LIMIT 5;

-- Count rows
SELECT COUNT(*) FROM users;

-- Count grouped by a column
SELECT role, COUNT(*) FROM users GROUP BY role;
```

### Joins

```sql
-- Show api keys with the owner's email
SELECT ak.label, ak.active, ak.last_used_at, u.email
FROM api_keys ak
JOIN users u ON ak.user_id = u.id;
```

### Editing Data (use carefully in dev)

```sql
-- Update a row
UPDATE users SET active = false WHERE email = 'ravi@blr.in';

-- Delete a row
DELETE FROM users WHERE email = 'ravi@blr.in';
```

### Other Handy Commands

| Command                        | Description                              |
|--------------------------------|------------------------------------------|
| `\timing`                      | Toggle query execution time display      |
| `\x`                           | Toggle expanded (vertical) row display   |
| `\e`                           | Open last query in your editor           |
| `\i <file.sql>`                | Run a SQL file                           |
| `TRUNCATE <table>;`            | Delete all rows from a table (fast)      |
| `\h <command>`                 | Help on a specific SQL command           |
| `\?`                           | Help on all psql backslash commands      |

## M1 Tables

| Table              | Purpose                                              |
|--------------------|------------------------------------------------------|
| `users`            | All actor accounts (10 roles)                        |
| `api_keys`         | Long-lived machine/service credentials (hashed)      |
| `role_audit_logs`  | Append-only log of every role change                 |
| `flyway_schema_history` | Flyway migration tracking                       |

## Authentication Methods

The API accepts two auth methods, checked in this order per request:

1. **API Key** — `X-Api-Key: <raw-key>` header. Long-lived, for integrations. Stored as SHA-256 hash in `api_keys`.
2. **JWT** — `Authorization: Bearer <token>` header. Short-lived (8h), issued on login.
