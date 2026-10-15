# Phase 1 — Identity, Authentication & RBAC Foundation

Scope: secure identity/authorization foundation only. No merchants, agents,
terminals, payment gateway, reconciliation, settlement, repairs, or analytics.

## 1. Authentication architecture

- **Stateless JWT (Bearer) + Spring Security.** No server sessions, no cookies.
  Login returns a signed JWT; the browser sends it as
  `Authorization: Bearer <token>`.
- **Token contents:** `sub` = stable database user id (`user_accounts.id`),
  plus an informational `role` claim. Default expiry: 120 minutes
  (`app.security.jwt.expiration-minutes`).
- **Authorization never trusts the token's role claim.** `JwtAuthenticationFilter`
  extracts the user id and reloads the account via `AppUserDetailsService`, so
  role changes take effect immediately and forged claims are useless.
- **Passwords:** BCrypt (`BCryptPasswordEncoder`). The `password` column stores
  only the hash; `@JsonIgnore` on the entity field is defense-in-depth — all
  APIs return DTOs that have no password field at all.
- **Failures:** JSON `401` (missing/invalid/expired token, bad credentials) via
  `RestAuthenticationEntryPoint`; JSON `403` (authenticated but not permitted)
  via `RestAccessDeniedHandler` / `ApiExceptionHandler`.
- **CSRF** is disabled (stateless Bearer API, no cookies). **CORS** allows only
  local dev origins with `allowCredentials=false`.

## 2. Role definitions (`model/Role.java`)

`ADMIN` · `LOAN_OFFICER` · `PAYMENTS_OPERATOR` · `AGENT` · `MERCHANT` ·
`TECHNICIAN` · `CUSTOMER`

- Persisted as the enum name; **add new roles by extending the enum** — no
  schema change needed.
- `Role.isStaff()` = ADMIN, LOAN_OFFICER, PAYMENTS_OPERATOR. AGENT / MERCHANT /
  TECHNICIAN / CUSTOMER are non-staff in Phase 1 because their cross-customer
  scoping (assigned customers, own transactions) has no data model yet.
- Granted authority is `ROLE_<NAME>`; use `@PreAuthorize("hasRole('ADMIN')")`.

## 3. Authorization model

| Endpoint | Rule |
|---|---|
| `POST /api/v1/auth/register`, `POST /api/v1/auth/login` | public |
| `GET /api/v1/devices` | any authenticated user |
| `POST /api/v1/devices`, `POST /api/v1/payment-plans`, `POST /api/v1/payment-modes` | `ADMIN` only |
| `GET /api/v1/payment-plans`, `GET /api/v1/payment-modes` | any authenticated user |
| `GET /api/v1/loans` (full ledger) | `ADMIN`, `LOAN_OFFICER` |
| `GET /api/v1/loans/me`, `GET /api/v1/loans/{id}` | owner, or staff |
| `POST /api/v1/loans/initialize` | self; `ADMIN`/`LOAN_OFFICER` may target another customer |
| `POST /api/v1/payments/receive` | owner-customer, `PAYMENTS_OPERATOR`, `ADMIN` |
| `POST /api/v1/cash-loans` | always recorded for the caller (no id in body) |
| `GET /api/v1/users/me`, `GET /api/v1/users/me/dashboard` | caller only (id from token) |
| `GET /api/v1/users/{id}/dashboard` | `ADMIN`, `LOAN_OFFICER` only |

## 4. Development admin & demo accounts

Created on startup by `IdentityBootstrap` when the email is not already present:

```properties
app.bootstrap.admin.email=${TRACOM_ADMIN_EMAIL:admin@tracom.local}
app.bootstrap.admin.password=${TRACOM_ADMIN_PASSWORD:Admin@1234}
app.bootstrap.demo-customer.email=${TRACOM_DEMO_EMAIL:demo@lipamdogo.com}
app.bootstrap.demo-customer.password=${TRACOM_DEMO_PASSWORD:Demo@1234}
```

Defaults are **academy/dev only** — override via environment variables anywhere
else. Passwords are hashed; only the email is logged, never the secret. A demo
cash loan is seeded for the demo customer on first run.

## 5. Frontend authentication

- `POST /api/v1/auth/login` → `{token, tokenType, expiresInSeconds, userId,
  customerCode, fullName, role}`. The token and profile are kept in memory and
  mirrored to `sessionStorage` (`tracom.token`, `tracom.user`). **Passwords are
  never stored.**
- Every API call adds `Authorization: Bearer <token>`.
- Self-service calls send **no identity at all**: `GET /users/me/dashboard`,
  `GET /loans/me`, `POST /loans/initialize {productId}`,
  `POST /cash-loans {amountRequested, monthlyRate, purpose}`,
  `POST /payments/receive {contractId, amount, ...}`.
- Any `401` clears the session and prompts re-login (`handleUnauthorized`).

## 6. Customer ownership enforcement (anti-IDOR/BOLA)

1. The `UserAccount.id` is the single authoritative identity. `LoanContract`
   and `CashLoan` reference it via `@ManyToOne`; name/timestamp/frontend-id
   joins are gone.
2. "Me" endpoints derive the user id from `@AuthenticationPrincipal
   UserPrincipal` — there is no id parameter to tamper with.
3. Endpoints that still take an `{id}`/`contractId` verify ownership in the
   service layer (`findByIdAndCustomerId`-style checks, explicit
   `AccessDeniedException`), with role gates (`@PreAuthorize`) on top.
4. `LoanContractRepository` exposes only list-based customer queries, so a
   second/third contract can never break lookups.

## 7. Test coverage of authorization

- `AuthIntegrationTest` — hashing, no password in responses, duplicate/weak
  input rejected, login success + generic 401 failures.
- `AuthorizationIntegrationTest` — unauthenticated → 401 (8 endpoints),
  invalid token → 401, cross-customer dashboard/loan/payment reads → 403,
  customer → 403 on staff endpoints, admin → allowed, admin origination.
- `MultiContractIntegrationTest` + `LoanServiceTest` — two contracts coexist,
  repository returns both, payments address one contract without touching the
  other, cross-customer creation denied.

## 8. Rules future phases MUST follow

1. Never return a JPA entity from a controller — always map to a DTO.
2. Never accept an identity (`customerId`, `userId`, `customerCode`) from the
   client for "self" operations; use the principal. `{id}` params are for
   staff endpoints with explicit authorization.
3. Every new customer-owned entity must `@ManyToOne` → `UserAccount` and query
   via list-based repository methods.
4. New roles go in the `Role` enum; update `isStaff()` and the endpoint matrix
   above, and add tests proving both the allow and the deny cases.
5. No plaintext secrets anywhere: config/env → `PasswordEncoder` → DB, never
   logged, never serialized.

## 9. Known Phase 1 limitations (for Phase 2)

- Partial/under-payments still flip a contract straight to `OVERDUE` without
  reducing the balance (old `LoanService` semantics kept intentionally).
- `CashLoan` still auto-approves; the working-capital review lifecycle is Phase 2.
- AGENT/MERCHANT/TECHNICIAN roles exist but have no scoped endpoints yet.
- `Product.paymentPlanName`/`paymentMode` remain loose strings, not relations.
- No audit logging yet; no refresh-token rotation (re-login after 120 min).
