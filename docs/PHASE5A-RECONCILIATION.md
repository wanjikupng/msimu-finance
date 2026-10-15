# Phase 5A — Payment Reconciliation

Scope: comparing internal payment records against a simulated provider feed.
No settlement, commissions, terminals, repairs, analytics, real integrations,
or automatic resolution. No financial side effects, ever, from this module.

> **Reconciliation identifies discrepancies; it does not automatically alter
> customer balances.**

## Purpose

The payment engine knows what *our application* says happened. Reconciliation
answers what the *provider* says happened, and whether the two agree.

```text
OUR SYSTEM                    PROVIDER FEED
PaymentTransaction     ↔      ProviderTransaction
       \                            /
        ↓                          ↓
            ReconciliationRecord
        (MATCHED / exceptions…)
```

## Models (kept separate by design)

- **PaymentTransaction** = our internal payment event (existing; untouched).
- **ProviderTransaction** (`provider_transactions`) = external evidence only:
  provider (PaymentChannel), providerReference, amount, currency (default
  KES), transactionTime, status (SUCCESS/FAILED/PENDING/REVERSED),
  optional rawMetadata, importedAt. No customer/contract/device/receipt data
  is duplicated here — those link through reconciliation.
- **ReconciliationRecord** (`reconciliation_records`) = one comparison
  result: nullable links to both sides, batch, status, differenceAmount,
  reason, reconciledAt. Nullable sides encode MISSING_* outcomes.
- **ReconciliationBatch** (`reconciliation_batches`) = one run:
  RECON-2026-000001, optional provider + time window, STARTED → PROCESSING →
  COMPLETED (FAILED only on engine error), with snapshot counts. Runs execute
  synchronously at prototype scale.

## Matching rules

- Primary correlation key: **providerReference**. Internal lookup uses the
  existing unique `payment_transactions.provider_reference`. Never matched
  by name, amount, timestamp, or frontend IDs.
- Internal channel must equal the feed provider, else STATUS_MISMATCH.
- Scope: provider rows selected by channel and `transactionTime` window;
  internal candidates are transactions carrying a provider reference (same
  channel filter, `requestedAt` window). Each side uses its own timestamps.

## Status mapping (provider → compatible internal)

| Provider     | Compatible internal      | Otherwise            |
|--------------|--------------------------|----------------------|
| SUCCESS      | SUCCESS or POSTED        | STATUS_MISMATCH      |
| FAILED       | FAILED                   | STATUS_MISMATCH      |
| PENDING      | INITIATED or PENDING     | STATUS_MISMATCH      |
| REVERSED     | REVERSED                 | STATUS_MISMATCH      |

Compatible states proceed to the amount check; anything else is a
STATUS_MISMATCH with both states named in the reason. Nothing is
auto-corrected — notably a provider REVERSED against our POSTED stays an
exception (reversal remains an explicit staff operation).

## Mismatch types

- **AMOUNT_MISMATCH** — states compatible, amounts differ. Difference =
  **provider amount minus internal amount** (BigDecimal; negative means the
  provider reports less). Currency disagreement also lands here (no silent
  conversion; internal amounts are KES).
- **MISSING_INTERNAL** — feed reference has no internal transaction. No
  payment is created: provider evidence ≠ authorized posting.
- **MISSING_PROVIDER** — internal transaction absent from the feed scope.
  No automatic reversal: the customer's payment stands.
- **DUPLICATE_PROVIDER** — same (provider, reference) occurs more than once
  in the reconciled scope; the primary reconciles normally, each surplus
  row gets its own exception. No second payment is created or posted.

## Batch lifecycle

`STARTED → PROCESSING → COMPLETED`, `FAILED` on unexpected engine error.
Counts (`totalRecords`, `matchedCount`, `exceptionCount`) snapshot at
completion. Records always reflect the latest run that covered them; batches
preserve per-run history.

## Idempotency

- **Feed import:** a row identical to a stored one (provider, reference,
  amount, transactionTime) is skipped (`skippedDuplicates`); only new rows
  insert (`inserted`). Importing the same feed twice changes nothing.
- **Runs:** one primary record per internal transaction and one per
  provider row, updated in place — including status transitions (e.g.
  MISSING_PROVIDER → MATCHED when the feed later arrives). Re-running the
  same scope rewrites identical outcomes without new rows or any financial
  effect.

## Duplicate handling

Identical repeats are absorbed at import (see above). Same reference with
differing content is stored and flagged DUPLICATE_PROVIDER at reconcile
time. The internal side is never duplicated or re-posted.

## Security / RBAC

All five endpoints live under `/api/v1/admin/reconciliation` with
class-level `ADMIN`/`PAYMENTS_OPERATOR`. No customer/agent/merchant/loan-
officer access; no customer-facing reconciliation endpoints exist, so other
customers' payment data is unreachable. Server-side enforcement only.

## Financial side-effect rule

The reconciliation service **reads** loan/payment tables and **writes only**
its own three tables. Verified by tests asserting `amountPaid`,
`amountRemaining`, and `LoanStatus` are bit-identical before and after every
mismatch run.

## Exception handling

Mismatches carry a human-readable `reason` (both states/amounts named) and,
for amount cases, a signed `differenceAmount`. Resolution (contact provider,
adjust, reverse via the existing staff flow) is deliberately out of scope.

## Prototype limitations

- Synchronous runs, no scheduling or async callbacks.
- Feed ingestion is a JSON POST (or UI textarea), not SFTP/API polling.
- No multi-currency conversion; non-KES provider amounts mismatch.
- Record history reflects the latest covering run per item (batches keep
  per-run counts, not per-run record copies).
- Duplicate detection is scoped to the reconciled feed selection.
- Recommended Phase 5B: **merchant settlement** — settlement batches,
  payouts, and commission accounting built on POSTED + reconciled data.
