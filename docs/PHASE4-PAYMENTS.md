# Phase 4 — Payment Transaction Engine, Posting, Receipts & Loan State

Scope: the payment lifecycle only. No settlement, reconciliation dashboards,
terminals, repairs, analytics, working-capital loans, or real integrations.

```text
Customer
   ↓
PaymentController (/payments)
   ↓
PaymentService (initiate / provider-result / queries)
   ↓
PaymentProviderRegistry → MockMpesaPaymentProvider (mock M-PESA only)
   ↓
PaymentTransaction (INITIATED → PENDING / SUCCESS / FAILED)
   ↓
PaymentPostingService (post / reverse / refreshLoanState)
   ↓
LoanContract (authoritative balance trio) → DeviceService (payoff only)
   ↓
PaymentReceipt (RCPT-…) — durable posting record
```

## 1. Core rule (enforced)

A REQUESTED payment is not a loan payment. A SUCCESS but UNPOSTED
transaction changes nothing. Only POSTED transactions move money, exactly
once. The old `LoanService.processPayment` direct-balance path is deleted;
`POST /payments/receive` is gone.

## 2. PaymentTransaction

`payment_transactions`: server-generated unique `PAY-2026-000001` (from row
id), `contract → LoanContract`, `customer → UserAccount` (both FKs, validated
consistent at initiation), `amount` (BigDecimal), `channel`,
`status`, unique `providerReference` (`MPESA-MOCK-…`, never the internal
identity), globally-unique `idempotencyKey`, `failureReason`,
`postedAmount` (actually applied), `reversalReference` (`REV-<txid>`) +
`reversalReason`, `requestedAt/completedAt/postedAt`, timestamps.

Channels: MPESA (working mock adapter), CARD/BANK/AGENT/POS/USSD (domain
values; initiation rejected with 400 — no fake integrations).

Statuses: INITIATED → PENDING | SUCCESS | FAILED; PENDING → SUCCESS | FAILED;
SUCCESS → POSTED; POSTED → REVERSED. Everything else (FAILED → POSTED,
POSTED → INITIATED, …) is rejected in the service layer. No generic
status-edit endpoint exists.

## 3. Mock M-PESA adapter

`PaymentProvider` interface + `PaymentProviderRegistry` (add-a-bean
extensibility; engine holds no provider logic). `MockMpesaPaymentProvider`
does no I/O, holds no credentials, moves no real money. Deterministic:
explicit `mockScenario` honored, AUTO → SUCCESS. The scenario is a documented
simulation knob on the mock (ignored by real adapters), never a client status
override — the lifecycle still runs. Never present as Safaricom processing.

## 4. Idempotency

Optional `Idempotency-Key` header (server UUID when absent; globally unique
column). Replay with the same key returns the original transaction (no second
charge); same key with different contract/amount/channel → 422. True
concurrent same-key inserts are guarded by the unique constraint (loser gets
400 and can replay to fetch the winner — documented limitation).

## 5. Posting

`PaymentPostingService.post`: lock tx → already POSTED returns existing
receipt (no-op) → require SUCCESS → lock contract → refuse empty contracts →
apply `min(amount, remaining)` → `paid +=`, `installment +=`, recompute
`remaining = total − paid` → roll covered installments → receipt
(`RCPT-2026-000001`, snapshots applied amount + balance after) → refresh
state. One transaction: failure anywhere rolls back (never "balance changed,
still SUCCESS"). Duplicate provider callbacks only move PENDING rows; posting
twice is a safe no-op.

## 6. Balance model (authoritative trio on LoanContract)

`totalPayable` (original obligation) + `amountPaid` (cumulative POSTED) +
`amountRemaining` (always recomputed, never independently edited).
Invariant: `paid + remaining = total`. Posting is the sole writer.

## 7. Allocation (DAILY cadence)

Each posting credits the installment due on `nextPaymentDueDate`; every fully
covered installment advances the due date one day and the remainder rolls
forward (`installmentPaid` field). Partials always count (60/70 → 60 credited,
40 arrears, date unchanged). Overpayment example: 250 on 100-daily with 60
carried → 310 credits four installments, due +4 days, 30 carried. Amounts
above the outstanding balance are rejected at initiation (no wallets/credit
balances in this phase).

## 8. Overdue & paid-off

`refreshLoanState` (runs after outcomes, postings, reversals — never on
reads): balance 0 → PAID_OFF; else past-due with incompletely paid
installment → OVERDUE; else ACTIVE. Recovery is automatic via allocation.
On payoff, `DeviceService` moves FINANCED/OVERDUE → PAID_OFF through the
existing validated transition (no direct writes; other device states are
left untouched with a warning log). The device stays customer-owned, never
returns to AVAILABLE.

## 9. Receipts

`PaymentReceipt` (separate entity, one per posting): receipt number,
transaction, applied amount, balance-after snapshot, posted timestamp.
Survives reversals as history. Response carries business identifiers
(contractNumber, deviceCode, customerCode) — no internal ids, no secrets.

## 10. Reversal (controlled, limited)

`POSTED → REVERSED` with mandatory reason (ADMIN/PAYMENTS_OPERATOR only):
paid/installment balances recomputed down, state refreshed (may reopen),
receipt retained, device untouched, no DELETE endpoint. Installment clock is
not moved backwards (documented limitation); full refund accounting is a
later phase.

## 11. PaymentHistory decision

Removed (entity, repository, table). `PaymentTransaction` is the single
authoritative record; the old history was write-only and is not missed.

## 12. Concurrency

Pessimistic write locks on transaction rows (initiate replay, provider
result, post, reverse) and contract rows (initiate, post, reverse). Tested:
simultaneous posts of one transaction → one receipt, effect once;
simultaneous distinct payments → correct sum. Balance is recomputed, never
blindly decremented.

## 13. Authorization

CUSTOMER: initiate own ACTIVE/OVERDUE contracts, view own, receipts for own
POSTED. Never post, provider-result, or reverse (403). PAYMENTS_OPERATOR:
post/reverse + staff list. ADMIN: all + provider-result simulation hook.
Cross-customer access: 403 on initiation, 404 in scoped reads.

## 14. API (all `/api/v1`, JWT)

`POST /payments` (201, Idempotency-Key) · `GET /payments/me[?status]` ·
`GET /payments/{ref}` · `GET /payments/{ref}/receipt` ·
`POST /payments/{ref}/post` (operator/admin) ·
`POST /payments/{ref}/provider-result` (admin simulation) ·
`POST /payments/{ref}/reverse` (operator/admin) ·
`GET /admin/payments[?status&contractId&customerId]` (operator/admin).

## 15. UI journeys

Customer: contract → amount → channel → demo scenario → pay (fresh
idempotency key per click) → result card distinguishes PENDING/FAILED/
SUCCESS/POSTED with plain-language notes → receipt → activity table +
dashboard summary (financed/paid/outstanding/expected/arrears/status) +
timeline (failed shown with reasons, never as paid). Staff: operations panel
lists actionable transactions with one-click posting of SUCCESS rows.

## 16. Test coverage

`PaymentEngineIntegrationTest` (13): immediate post+receipt, PENDING/FAILED
zero-effect, SUCCESS-unposted zero-effect → explicit post once, partials,
multi-pay invariant, allocation roll, payoff+device, boundary rejections
(0/over/none/foreign/channel/paid-off), idempotent replay + 422 mismatch,
reversal + no-DELETE, overdue + dashboard summary, staff scoping, two
concurrency proofs. All Phase 1–3 suites ported (old receive endpoint gone)
and green.

## Payment Reservation

Initiation used to validate `amount <= amountRemaining`, ignoring money
already promised to earlier unposted payments — a deferred payment could be
silently short-applied at posting via `min()`. Fixed in Phase 4.1A:

- **Which transaction states reserve funds:** INITIATED, PENDING, and
  unposted SUCCESS. These are the states whose amounts may still become
  financially effective. POSTED is consumed into the balance trio, FAILED
  is released, REVERSED is rolled back — none of those reserve.
- **How available payment capacity is calculated** (server-side, per
  initiation, never from the client):
  `available = amountRemaining − SUM(amount of reserving transactions)`.
  Requests above `available` are rejected with 400; nothing is reserved
  beyond the outstanding balance.
- **How failed transactions release reservations:** no code path needed —
  FAILED simply isn't in the reserving set, so the next SUM no longer
  counts it. Contract balances are untouched by failure.
- **How posted transactions consume reservations:** posting reduces
  `amountRemaining` by the applied amount, so the same SUM mechanically
  excludes the posted row (now POSTED) while the smaller remaining balance
  keeps `posted + reserved <= total` intact. No double-counting.
- **How concurrent requests are protected:** initiation holds the existing
  pessimistic write lock on the contract row while computing availability
  and inserting the reservation, so simultaneous requests serialize; the
  loser sees the winner's committed reservation and is rejected. The
  idempotency key is additionally re-checked under that lock, closing the
  same-key race. No new locking strategy was introduced.
- **How idempotency interacts with reservation:** a same-key retry returns
  the original transaction (no second reservation); a same-key request
  with different terms is rejected with 422. Derived state, so there is no
  `reservedAmount` column to drift — `SUM … WHERE status IN (…)` over the
  indexed `contract_id` is the single source of truth.
- **Posting is strict:** the old `min(amount, remaining)` trim is gone. If
  a SUCCESS transaction ever exceeds the remaining balance at post time,
  posting throws `IllegalStateException` and applies nothing — a loud
  consistency failure, never a silent partial payment.
- **Invariant (tested):** `posted + reserved + available = total payable`
  at all times; equivalently `posted + reserved <= original obligation`.
## Payment Allocation

A posted payment used to leave no trace of *how* it was applied, so reversal
could only subtract aggregates — unable to restore installment schedules
exactly. Phase 4.1B introduces the explicit allocation ledger:

- **Why allocations exist:** to record, per posted payment, which day
  obligations each shilling satisfied, making every reversal exact and every
  multi-payment history reconstructible.
- **Allocation order:** oldest unpaid day first (fill the current day's
  remainder, then whole days, then a final partial day). The server decides;
  the frontend never selects allocation dates.
- **Partial-payment allocation:** a 60 payment on a 70 day creates one
  60 row; a later 40 completes that day (+10) and starts the next (+30).
- **Multi-installment allocation:** 250 at daily 70 creates
  70 + 70 + 70 + 40 across four consecutive days starting at the then-due
  date. Rows always sum exactly to the payment (asserted at posting).
- **Reversal behavior:** the payment's ACTIVE rows are marked REVERSED
  (preserved with `reversedAt`, never deleted); the schedule is rebuilt by
  replaying the remaining ACTIVE rows forward from the contract's
  `schedule_anchor_date` with identical roll arithmetic — the exact state as
  if the reversed payment had never posted, with unrelated payments
  untouched. Transaction amount/reference/timestamps stay immutable; only
  the lifecycle flips POSTED → REVERSED.
- **Payoff reversal rule:** reversing on a PAID_OFF contract is refused
  with *"This payment cannot be reversed because it completed the financing
  contract. A controlled finance adjustment is required."* — a controlled
  refusal instead of an impossible Loan-ACTIVE + Device-PAID_OFF state.
  Otherwise the device is untouched by reversal.
- **Balance reconstruction:** `amountPaid`/`amountRemaining` keep their
  incremental update; `(dueDate, installmentPaid)` come from ledger replay;
  `refreshLoanState` then derives ACTIVE/OVERDUE. Invariant holds:
  balance = total − net posted allocations.
- **Historical immutability:** receipts stay retrievable after reversal;
  the timeline shows REVERSED with the original receipt link.
- **Limitations:** reversal cannot rewind the installment clock beyond what
  the replay reproduces (it reproduces it exactly, so this is now moot for
  schedule state); payoff reversal requires a future finance-adjustment
  flow; single-currency KES.
- **Coverage:** `PaymentAllocationTest` (13): single/partial/multi-day
  allocation, deterministic multi-payment splits, exact schedule restore,
  partial reversal, cross-payment independence, overdue restoration,
  payoff refusal without state change, duplicate-reversal rejection,
  receipt survival, and concurrent reverse-vs-reverse / post-vs-reverse
  coherence.

## 17. Known limitations (for later phases)

- No async provider callbacks (simulation hook instead); no wallets/refunds
  (overpayments rejected; reversal doesn't move the installment clock back
  or touch the device); reversal can't re-fire posting; single-currency
  (KES) BigDecimal; summary arrears are point-in-time reads.
- Recommended Phase 5: **reconciliation + settlement** — provider-side
  statements matched against POSTED transactions, exceptions queue,
  merchant/operator settlement batches built from receipts.
