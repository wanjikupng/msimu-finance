# Phase 3 — Financing Application, Approval & Contract Lifecycle

Scope: the financing workflow only. No payment engine, reconciliation,
settlement, terminals, repairs, analytics, or credit scoring.

## 1. The core change

`POST /loans/initialize` is gone (endpoint, service method, and DTO removed).
Customers can no longer create live contracts. The only path to an ACTIVE
contract is: application → officer review → approval, executed atomically in
`FinancingService`.

## 2. FinancingApplication model

`financing_applications`: `applicationNumber` (unique, `APP-2026-000001`,
derived from the row id after flush — race-free and portable),
`customer → UserAccount`, `device → Device`, `status`, `paymentPlan →
PaymentPlan` (required), `paymentMode → PaymentMode` (optional),
`devicePrice` + `depositAmount` + `requestedPrincipal` (locked at submission),
`approvedPrincipal` (written at approval), `reviewedBy → UserAccount`,
`decisionReason`, `submittedAt`, `reviewedAt`, timestamps.

## 3. Application lifecycle

`SUBMITTED → UNDER_REVIEW → APPROVED | REJECTED`, plus customer `CANCELLED`
from SUBMITTED/UNDER_REVIEW. Blocking (device-locking) states: SUBMITTED,
UNDER_REVIEW, APPROVED. Terminal: REJECTED, CANCELLED (device released to
AVAILABLE through `DeviceService.releaseReservation`).

## 4. Approval lifecycle (all-or-nothing transaction)

`approveApplication` (officer/admin only): write-lock application →
require UNDER_REVIEW → write-lock device → require RESERVED (warehouse-direct)
or ASSIGNED (channel-held) → require no live contract on the device → create
contract from the locked application snapshot → `handoverToCustomer` →
mark APPROVED with reviewer + timestamp. Any failure rolls back; a concurrent
second approval finds the device no longer reserved and fails.

## 5. Contract lifecycle

`LoanStatus` stays ACTIVE / OVERDUE / PAID_OFF (the only states with live
producers/consumers in Phase 3). Contracts are born ACTIVE at approval.
New snapshot columns on `loan_contracts`: `contract_number` (`LN-2026-000001`),
`application_id`, `principal_amount`, `total_payable`, `payment_frequency`
(DAILY), `device_price`, `plan_name`, `plan_duration_days`. `createdAt` is the
contract start. PENDING/CANCELLED/DEFAULTED are intentionally absent until a
phase needs them.

## 6. Device interaction (decision)

Warehouse-direct financing: `AVAILABLE → RESERVED` (submit) → `FINANCED`
(approval via new `DeviceService.handoverToCustomer`, which sets the customer,
clears any channel agent, keeps merchant provenance). This required adding the
`RESERVED → FINANCED` edge to the Phase 2 transition map. Channel-held units
use the existing `ASSIGNED → FINANCED` path. All moves go through
`DeviceService` — never direct column writes.

## 7. Financial calculation rules (`FinancingCalculationService`)

Single authority, `BigDecimal` only: deposit ≥ 0, deposit < device price,
deposit ≥ product minimum (`downPayment`); principal = price − deposit;
installment = plan daily amount, cadence DAILY; total payable = principal
(no interest/fees in this prototype). The frontend quote endpoint displays
these figures; submission re-validates everything server-side. No credit
scores, no AI claims — the loan officer decides.

## 8. One-active-contract-per-device

Enforced twice: submit rejects when a blocking application or a live
(ACTIVE/OVERDUE) contract exists for the device; approval re-checks both under
write locks. Customers keep unlimited applications/contracts across *different*
devices.

## 9. Concurrency strategy

Pessimistic write locks (`findByIdForUpdate`) on the device row at submit and
on both application + device rows at review/approve/reject/cancel. Concurrent
racers serialize on the row lock; losers fail validation instead of
double-committing. Unique DB constraints (application/contract numbers, IMEI)
backstop races the locks cannot see.

## 10. Authorization model

- CUSTOMER: submit (own), view own, cancel own undecided, view own contracts.
  No review/approve/reject (403), no cross-customer reads (404 in `/me`
  scope), no status manipulation (no endpoint exists for it).
- LOAN_OFFICER + ADMIN: list/detail/review/approve/reject + summary.
  Reviewer identity is always the principal, never request data.
- PAYMENTS_OPERATOR / AGENT / MERCHANT / TECHNICIAN: no financing powers.

## 11. API surface (all `/api/v1`, JWT)

Customer: `GET /financing/eligible-devices`, `GET /financing/payment-plans`,
`GET /financing/payment-modes`, `GET /financing/quote`,
`POST /financing/applications`, `GET /financing/applications/me`,
`GET /financing/applications/me/{id}`, `POST …/me/{id}/cancel`.
Staff: `GET /admin/financing/applications[?status]`, `GET …/{id}`,
`POST …/{id}/review|approve|reject`, `GET …/applications/summary`.
Contracts: `GET /loans[/me|/{id}]` (unchanged shape, richer snapshot fields).

## 12. Customer & officer journeys

Customer: sign in → Financing panel (eligible device + plan + deposit, live
server quote) → submit → application number + SUBMITTED → track in
Applications table → cancel while undecided. After approval the contract
appears in the ledger and daily-payment simulation works against it.
Officer/admin: review queue (filter + counters) → inspect (masked IMEI,
locked terms) → start review → approve (contract activates) or reject with a
mandatory reason.

## 13. Test coverage

`FinancingWorkflowIntegrationTest` (13): submit+reserve, duplicate block,
6-case input validation, review→approve snapshot assertions, double-approval
block, reject (reason/release/no-contract), cancel, customer-decision 403s,
cross-customer 404s, stale-device rollback integrity, live-contract block,
two-device journey, summary counts, server quote. `LoanServiceTest`,
`AuthorizationIntegrationTest`, `MultiContractIntegrationTest` ported to the
approval flow. All Phase 1/2 suites pass unchanged in behavior.

## 14. Assumptions & limits

- Terms lock at submission; catalogue changes affect new applications only.
- Approval hands over immediately (no separate delivery step — a future
  handover module can split APPROVED from FINANCED).
- Officer cannot edit terms (no partial-approval amounts); rejection reason
  is free text, not a scoring model.
- `CashLoan` untouched per phase scope.
