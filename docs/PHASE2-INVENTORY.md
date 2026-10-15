# Phase 2 — Agents, Merchants & Physical Device Inventory

Scope: real-world actors + physical inventory only. No payment engine,
reconciliation, settlement, terminals, repairs, or analytics overhaul.

## 1. Domain model

```text
UserAccount (auth identity, Phase 1)
  ├── Agent (1:1 profile; role AGENT) ──belongs to──> Merchant
  ├── Merchant.owner (optional; role MERCHANT or ADMIN)
  ├── Device.customer (optional; the holder)
  └── LoanContract.customer (Phase 1, unchanged)

Product (catalogue model, e.g. "Samsung Galaxy A15")
  └── Device (physical unit: IMEI + serial + deviceCode)

Merchant ──< Agent (many)
Merchant ──< Device (many, current holder channel)
Agent ──< Device (many, current handler)
UserAccount (customer) ──< Device (many holders)
LoanContract ──> Device (nullable; reserved for the Phase 3 financing link)
```

`User ≠ Agent ≠ Merchant ≠ Product ≠ Device ≠ Loan` — each is a separate
entity with explicit relationships. No name-string joins anywhere.

## 2. Statuses

- `LoanStatus` (renamed from `DeviceStatus`): ACTIVE / OVERDUE / PAID_OFF.
  Describes the **financing**, stored in `loan_contracts.loan_status`.
- `DeviceStatus` (physical asset): AVAILABLE / RESERVED / ASSIGNED / FINANCED /
  OVERDUE / PAID_OFF / REPAIR / LOST / RETIRED. Meanings are documented on the
  enum. The two state machines are **not hardwired**: loan OVERDUE with device
  FINANCED is temporarily valid.
- `MerchantStatus`: PENDING / ACTIVE / SUSPENDED / CLOSED.
- `AgentStatus`: PENDING / ACTIVE / SUSPENDED / TERMINATED.

## 3. Device lifecycle (enforced in `DeviceService`)

```text
AVAILABLE → RESERVED | ASSIGNED | RETIRED
RESERVED → AVAILABLE | ASSIGNED | FINANCED (warehouse-direct handover) | RETIRED
ASSIGNED → FINANCED | AVAILABLE(release) | REPAIR | LOST | RETIRED
FINANCED → OVERDUE | PAID_OFF | REPAIR | LOST
OVERDUE → FINANCED | PAID_OFF | REPAIR | LOST
REPAIR → AVAILABLE | ASSIGNED | RETIRED
LOST → RETIRED
PAID_OFF, RETIRED → terminal (no outgoing transitions)
```

- Assignment is the only way to set relationships: merchant/agent assignment
  requires an ACTIVE merchant (and ACTIVE agent); customer handover requires
  ASSIGNED → FINANCED.
- `PATCH …/status` handles relationship-free moves only; ASSIGNED/FINANCED are
  rejected there with 400.
- Every mutation lives in `DeviceService` (single future audit hook point).

## 4. Identity rules

- `deviceCode` / `merchantCode` / `agentCode` are server-generated, unique.
- IMEI (10–16 digits) and serial (letters/digits/dashes) are optional but
  unique when present; duplicates → 400 (service check + DB constraint +
  `DataIntegrityViolationException` safety net).
- Phase 1 auth is untouched: agents/merchants log in as `UserAccount`s;
  `POST /api/v1/admin/users` (ADMIN) mints AGENT/MERCHANT/… accounts.
  Self-registration stays CUSTOMER-only.

## 5. API surface (all under `/api/v1`, JWT required)

| Area | Endpoints |
|---|---|
| Admin merchants | `GET/POST /admin/merchants`, `GET/PATCH /admin/merchants/{id}` (`?status=`) |
| Admin agents | `GET/POST /admin/agents`, `GET/PATCH /admin/agents/{id}` (`?merchantId=`) |
| Admin devices | `GET/POST /admin/devices` (filters: `status productId merchantId agentId customerId`), `GET /admin/devices/{id}`, `POST …/{id}/assign`, `POST …/{id}/release`, `PATCH …/{id}/status` |
| Admin users | `POST /admin/users` |
| Agent scope (`AGENT`) | `/agents/me`, `/agents/me/merchant`, `/agents/me/devices`, `/agents/me/devices/{id}` |
| Merchant scope (`MERCHANT`) | `/merchants/me`, `/merchants/me/agents`, `/merchants/me/devices`, `/merchants/me/devices/{id}` |
| Customer scope | `/customer/devices`, `/customer/devices/{id}` |
| Catalogue | `/products` (canonical) + legacy `/devices` alias |

Scoped detail endpoints return **404** for out-of-scope ids (existence is not
revealed); role violations return **403**; missing auth returns **401**.

## 6. Seed data (`DomainBootstrap`, after `IdentityBootstrap`)

QuickMart Electronics + TechPoint merchants (ACTIVE, owners linked), agents
Amina/Brian → QuickMart, Faith → TechPoint, and 7 devices across Galaxy A15 /
Redmi 14C / Tecno Spark 10 in AVAILABLE / ASSIGNED / FINANCED / REPAIR states
(demo customer holds TRC-000131). Logins: `amina.agent@tracom.local`,
`quickmart@tracom.local`, … (passwords via `TRACOM_AGENT_PASSWORD` /
`TRACOM_MERCHANT_PASSWORD`, dev defaults documented).

## 7. Test coverage

`MerchantAgentDeviceIntegrationTest` (8 tests): merchant create/suspend +
customer forbidden, agent linkage + one-profile-per-account + role enforcement,
device create + duplicate/format rejection + customer forbidden, full
ASSIGNED → FINANCED → PAID_OFF lifecycle + terminal-state enforcement,
suspended-channel refusal, cross-customer/merchant/agent isolation (404/403),
multi-device customers, admin filters, no sensitive fields in responses.
All 18 Phase 1 tests still pass unmodified in behavior (only the
`DeviceStatus` → `LoanStatus` rename touched one assertion).

## 8. Rules Phase 3 MUST follow

1. Link financing approval to `LoanContract.device` (nullable FK is ready);
   one device → one active financing relationship.
2. Mirror loan outcomes onto `Device.status` (OVERDUE/PAID_OFF/FINANCED)
   through `DeviceService`, never by writing the column elsewhere.
3. Keep every new state change inside its service; no controller-side mutation.
4. Extend the endpoint matrix in `docs/PHASE1-AUTH.md` §3 with the table above
   and keep the allow+deny test pattern for every new boundary.
5. `Product.paymentPlanName/paymentMode` are still display-only loose strings;
   the payment-plan redesign owns their replacement.

## 9. Known limitations

- No return path from PAID_OFF (customer-owned devices stay terminal until a
  future return/release operation exists).
- One owner → "my merchant" resolves to the first linked merchant; multi-shop
  owners need a selector later.
- Suspended agents keep already-assigned devices (only new assignments stop).
